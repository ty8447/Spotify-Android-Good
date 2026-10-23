package com.taehagen.spotifygood.ui.screens.menu;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Minimal, dependency-free, fixed-version QR encoder for Spotify Jam invitation URLs.
 * QR version 5, error correction M, byte mode, mask 0; 37x37 modules.
 * Does not transmit or log the invitation URL. Input must be verified upstream.
 */
public final class JamQrEncoder {
    public static final int SIZE = 37;
    private static final int DATA_CODEWORDS = 86;
    private static final int BLOCK_DATA = 43;
    private static final int ECC_CODEWORDS = 24;

    private JamQrEncoder() {}

    public static boolean[][] encode(String url) {
        if (url == null || !(url.matches("https://open\\.spotify\\.com/socialsession/[A-Za-z0-9]{22}")
                || url.matches("https://spotify\\.link/[A-Za-z0-9]{6,55}"))) {
            throw new IllegalArgumentException("Expected a verified Spotify Jam invitation URL");
        }
        byte[] input = url.getBytes(StandardCharsets.US_ASCII);
        if (input.length > 84) throw new IllegalArgumentException("QR payload exceeds capacity");
        int[] data = new int[DATA_CODEWORDS];
        int bit = 0;
        bit = putBits(data, bit, 0x4, 4);   // byte mode
        bit = putBits(data, bit, input.length, 8);
        for (byte b : input) bit = putBits(data, bit, b & 255, 8);
        bit = Math.min(DATA_CODEWORDS * 8, bit + 4); // terminator; unwritten bits are zero
        bit = (bit + 7) & ~7;
        for (int i = bit / 8; i < data.length; i++) data[i] = ((i - bit / 8) & 1) == 0 ? 0xEC : 0x11;

        int[] divisor = rsDivisor(ECC_CODEWORDS);
        int[][] blocks = new int[2][BLOCK_DATA + ECC_CODEWORDS];
        for (int b = 0; b < 2; b++) {
            System.arraycopy(data, b * BLOCK_DATA, blocks[b], 0, BLOCK_DATA);
            int[] ec = rsRemainder(blocks[b], BLOCK_DATA, divisor);
            System.arraycopy(ec, 0, blocks[b], BLOCK_DATA, ECC_CODEWORDS);
        }
        int[] encoded = new int[2 * (BLOCK_DATA + ECC_CODEWORDS)];
        int out = 0;
        for (int i = 0; i < BLOCK_DATA + ECC_CODEWORDS; i++) {
            encoded[out++] = blocks[0][i];
            encoded[out++] = blocks[1][i];
        }
        int[][] grid = new int[SIZE][SIZE];
        for (int[] row : grid) Arrays.fill(row, -1);
        finder(grid, 0, 0);
        finder(grid, 0, SIZE - 7);
        finder(grid, SIZE - 7, 0);
        for (int i = 8; i < SIZE - 8; i++) {
            if (grid[6][i] < 0) grid[6][i] = (i % 2 == 0) ? 1 : 0;
            if (grid[i][6] < 0) grid[i][6] = (i % 2 == 0) ? 1 : 0;
        }
        alignment(grid, 30, 30);
        // Format bits: level M = 00, mask pattern 0 = 000, BCH(0) XOR 0x5412.
        final int format = 0x5412;
        for (int i = 0; i < 15; i++) {
            int value = (format >>> i) & 1;
            int verticalRow = i < 6 ? i : i < 8 ? i + 1 : SIZE - 15 + i;
            int horizontalCol = i < 8 ? SIZE - i - 1 : i == 8 ? 7 : 15 - i - 1;
            grid[verticalRow][8] = value;
            grid[8][horizontalCol] = value;
        }
        grid[SIZE - 8][8] = 1;
        int byteIndex = 0, bitIndex = 7;
        int row = SIZE - 1, direction = -1;
        for (int rightCol = SIZE - 1; rightCol > 0; rightCol -= 2) {
            if (rightCol == 6) rightCol = 5;  // skip vertical timing column
            while (true) {
                for (int col = rightCol; col >= rightCol - 1; col--) {
                    if (grid[row][col] >= 0) continue;
                    int value = byteIndex < encoded.length ? (encoded[byteIndex] >>> bitIndex) & 1 : 0;
                    if (((row + col) & 1) == 0) value ^= 1; // QR mask 0
                    grid[row][col] = value;
                    if (--bitIndex < 0) { bitIndex = 7; byteIndex++; }
                }
                row += direction;
                if (row < 0 || row >= SIZE) { row -= direction; direction = -direction; break; }
            }
        }
        boolean[][] result = new boolean[SIZE][SIZE];
        for (int y = 0; y < SIZE; y++) for (int x = 0; x < SIZE; x++) result[y][x] = grid[y][x] == 1;
        return result;
    }

    private static int putBits(int[] data, int offset, int value, int length) {
        for (int i = length - 1; i >= 0; i--) {
            if (((value >>> i) & 1) != 0) data[offset >>> 3] |= 1 << (7 - (offset & 7));
            offset++;
        }
        return offset;
    }

    private static void finder(int[][] grid, int top, int left) {
        for (int dy = -1; dy <= 7; dy++) for (int dx = -1; dx <= 7; dx++) {
            int row = top + dy, col = left + dx;
            if (row < 0 || col < 0 || row >= SIZE || col >= SIZE) continue;
            boolean black = (dy >= 0 && dy <= 6 && (dx == 0 || dx == 6)) ||
                (dx >= 0 && dx <= 6 && (dy == 0 || dy == 6)) ||
                (dy >= 2 && dy <= 4 && dx >= 2 && dx <= 4);
            grid[row][col] = black ? 1 : 0;
        }
    }

    private static void alignment(int[][] grid, int centerY, int centerX) {
        for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) {
            grid[centerY + dy][centerX + dx] =
                (Math.abs(dy) == 2 || Math.abs(dx) == 2 || (dx == 0 && dy == 0)) ? 1 : 0;
        }
    }

    private static int gfMul(int a, int b) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ (((z >>> 7) & 1) * 0x11D);
            z ^= (((b >>> i) & 1) * a);
        }
        return z;
    }

    private static int[] rsDivisor(int degree) {
        int[] result = new int[degree + 1];
        result[0] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            int[] next = new int[degree + 1];
            for (int j = 0; j <= i; j++) {
                next[j] ^= result[j];
                next[j + 1] ^= gfMul(result[j], root);
            }
            result = next;
            root = gfMul(root, 0x02);
        }
        return result;
    }

    private static int[] rsRemainder(int[] data, int length, int[] divisor) {
        int[] result = new int[ECC_CODEWORDS];
        for (int i = 0; i < length; i++) {
            int factor = data[i] ^ result[0];
            System.arraycopy(result, 1, result, 0, result.length - 1);
            result[result.length - 1] = 0;
            for (int j = 0; j < result.length; j++) result[j] ^= gfMul(divisor[j + 1], factor);
        }
        return result;
    }
}
