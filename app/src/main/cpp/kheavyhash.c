/**
 * kHeavyHash implementation for Kaspa mining.
 *
 * Matches the rusty-kaspa reference implementation:
 *   1. cSHAKE256("ProofOfWorkHash", 80-byte input) -> hash1
 *   2. Generate 64x64 matrix from hash1 via XoShiRo256++ (with rank check)
 *   3. Split hash1 into 64 nibbles (4-bit values)
 *   4. Matrix-vector multiply (both shifts >> 10)
 *   5. XOR product with hash1
 *   6. cSHAKE256("HeavyHash", 32-byte result) -> final hash
 *
 * Verified against Python cSHAKE256 reference + rusty-kaspa bridge.
 * Test vector: pre_pow=[0x2A]*32, ts=5435345234, nonce=432432432
 *   -> PowHash first 16: 2fb72b63dd0dd0d82b00cd9f83d4eca0
 *   -> Full kHeavyHash:  5a5bcd6e352eb8c87c80d0f0574a45a5
 *
 * Copyright (c) 2026 OverBuild Labs
 */

#include "kheavyhash.h"
#include "keccak.h"
#include <string.h>
#include <math.h>

/* ===== XoShiRo256++ PRNG (for matrix generation) ===== */
/* MUST be PlusPlus (++) variant — Kaspa/rusty-kaspa requires it. */

typedef struct {
    uint64_t s[4];
} XoShiRo256PlusPlus;

static inline uint64_t xoshiro_rotl(uint64_t x, int k) {
    return (x << k) | (x >> (64 - k));
}

static void xoshiro_init(XoShiRo256PlusPlus *rng, const uint8_t *hash) {
    for (int i = 0; i < 4; i++) {
        rng->s[i] = 0;
        for (int j = 0; j < 8; j++) {
            rng->s[i] |= ((uint64_t)hash[i * 8 + j]) << (j * 8);
        }
    }
}

static uint64_t xoshiro_next(XoShiRo256PlusPlus *rng) {
    /* PlusPlus (++) variant: rotl(s[0] + s[3], 23) + s[0] */
    const uint64_t result = xoshiro_rotl(rng->s[0] + rng->s[3], 23) + rng->s[0];
    const uint64_t t = rng->s[1] << 17;

    rng->s[2] ^= rng->s[0];
    rng->s[3] ^= rng->s[1];
    rng->s[1] ^= rng->s[2];
    rng->s[0] ^= rng->s[3];
    rng->s[2] ^= t;
    rng->s[3] = xoshiro_rotl(rng->s[3], 45);

    return result;
}

/* ===== Matrix generation with rank check ===== */

static void matrix_fill(HeavyMatrix *matrix, XoShiRo256PlusPlus *rng) {
    for (int i = 0; i < 64; i++) {
        for (int j = 0; j < 64; j += 16) {
            uint64_t val = xoshiro_next(rng);
            for (int shift = 0; shift < 16; shift++) {
                matrix->data[i][j + shift] = (val >> (4 * shift)) & 0x0F;
            }
        }
    }
}

static int matrix_compute_rank(const HeavyMatrix *matrix) {
    const double EPS = 1e-9;
    double mat_float[64][64];
    bool row_selected[64];
    int rank = 0;

    for (int i = 0; i < 64; i++)
        for (int j = 0; j < 64; j++)
            mat_float[i][j] = (double)matrix->data[i][j];

    memset(row_selected, 0, sizeof(row_selected));

    for (int i = 0; i < 64; i++) {
        int j = 0;
        while (j < 64) {
            if (!row_selected[j] && fabs(mat_float[j][i]) > EPS) break;
            j++;
        }
        if (j != 64) {
            rank++;
            row_selected[j] = true;
            for (int p = i + 1; p < 64; p++) mat_float[j][p] /= mat_float[j][i];
            for (int k = 0; k < 64; k++) {
                if (k != j && fabs(mat_float[k][i]) > EPS) {
                    for (int p = i + 1; p < 64; p++)
                        mat_float[k][p] -= mat_float[j][p] * mat_float[k][i];
                }
            }
        }
    }
    return rank;
}

static void matrix_generate(HeavyMatrix *matrix, const uint8_t *hash) {
    XoShiRo256PlusPlus rng;
    xoshiro_init(&rng, hash);
    do {
        matrix_fill(matrix, &rng);
    } while (matrix_compute_rank(matrix) != 64);
}

/* ===== kHeavyHash core ===== */

void kheavyhash_init(KHeavyHashState *state) {
    memset(state->last_hash1, 0, 32);
    state->matrix_valid = false;
}

void kheavyhash_compute(KHeavyHashState *state, const uint8_t *input, uint8_t *output) {
    uint8_t hash1[32];
    uint8_t vec[64];
    uint8_t product[32];

    /* Step 1: cSHAKE256("ProofOfWorkHash", 80-byte input) -> hash1 */
    cshake256_hash((const uint8_t *)"ProofOfWorkHash", 15, input, 80, hash1);

    /* Step 2: Generate matrix from pre_pow_hash (first 32 bytes of input).
     * Cache: only regenerate when pre_pow_hash changes (new job). */
    if (!state->matrix_valid || memcmp(input, state->last_hash1, 32) != 0) {
        matrix_generate(&state->matrix, input);
        memcpy(state->last_hash1, input, 32);
        state->matrix_valid = true;
    }

    /* Step 3: Split hash1 into 64 nibbles (4-bit values) */
    for (int i = 0; i < 32; i++) {
        vec[2 * i]     = hash1[i] >> 4;
        vec[2 * i + 1] = hash1[i] & 0x0F;
    }

    /* Step 4: Matrix-vector multiply — BOTH shifts are >> 10 */
    for (int i = 0; i < 32; i++) {
        uint16_t sum1 = 0;
        uint16_t sum2 = 0;
        for (int j = 0; j < 64; j++) {
            sum1 += state->matrix.data[2 * i][j] * vec[j];
            sum2 += state->matrix.data[2 * i + 1][j] * vec[j];
        }
        product[i] = (uint8_t)(((sum1 >> 10) << 4) | (sum2 >> 10));
    }

    /* Step 5: XOR with original hash */
    for (int i = 0; i < 32; i++) {
        product[i] ^= hash1[i];
    }

    /* Step 6: cSHAKE256("HeavyHash", 32-byte result) -> final hash */
    cshake256_hash((const uint8_t *)"HeavyHash", 9, product, 32, output);
}

/* ===== Difficulty utilities ===== */

bool check_difficulty(const uint8_t *hash, const uint8_t *target) {
    /* Compare as LE Uint256: byte[31] is MSB, byte[0] is LSB.
     * Matches rusty-kaspa's Uint256::from_le_bytes() comparison. */
    for (int i = 31; i >= 0; i--) {
        if (hash[i] < target[i]) return true;
        if (hash[i] > target[i]) return false;
    }
    return true;
}

void set_target_from_difficulty(double difficulty, uint8_t *target) {
    /* NaN and +/-inf slip past a plain `<= 0` test; treat any non-finite or
     * non-positive value as the old floor rather than hashing against garbage. */
    if (!(difficulty > 0.0) || !isfinite(difficulty)) difficulty = 0.0001;

    /* target = (2^224 - 1) / difficulty, as a 256-bit little-endian integer.
     * This is the rusty-kaspa bridge's share target.
     *
     * The previous version truncated 1/difficulty to an integer, which is off
     * by up to a few percent for fractional difficulties and collapses every
     * difficulty above 1 to the difficulty-1 target (so the pool would reject
     * nearly every share). Instead, split difficulty = mant * 2^exp with
     * mant in [0.5, 1): then 2^224 / difficulty = (1/mant) * 2^(224 - exp).
     * 1/mant is in (1, 2], so q = (1/mant) * 2^62 is a 63/64-bit integer that
     * keeps the full double precision; it is placed at bit 224 - exp - 62.
     * Using 2^224 instead of 2^224 - 1 changes the result by under 1 ulp. */

    memset(target, 0, 32);

    int exp;
    double mant = frexp(difficulty, &exp);
    uint64_t q = (uint64_t)ldexp(1.0 / mant, 62);
    int shift = 224 - exp - 62;

    if (shift + 63 >= 256) {
        /* Easier than the largest representable target: accept everything. */
        memset(target, 0xFF, 32);
        return;
    }
    for (int bit = 0; bit < 64; bit++) {
        if (!((q >> bit) & 1)) continue;
        int pos = shift + bit;
        if (pos >= 0) target[pos / 8] |= (uint8_t)(1u << (pos % 8));
    }
}
