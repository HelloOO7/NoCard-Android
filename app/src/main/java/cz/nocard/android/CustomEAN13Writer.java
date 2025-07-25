package cz.nocard.android;

import com.google.zxing.FormatException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.oned.OneDimensionalCodeWriter;

import java.util.ArrayList;
import java.util.List;

/**
 * <a href="https://stackoverflow.com/questions/24408954/zxing-ean13-barcode-encoding-with-lead-separator-and-trailer">Source</a>
 * <p>
 * This is a custom implementation as the customer wants a modified barcode with longer and shorter lines for start, middle and end.
 * Most code is based on Code from the OneDimensionalCodeWriter, EAN13Writer and UPCEANReader but
 * had to be copied as the methods were package private
 */
public class CustomEAN13Writer extends OneDimensionalCodeWriter {

    // For an EAN-13 barcode, the first digit is represented by the parities used
// to encode the next six digits, according to the table below. For example,
// if the barcode is 5 123456 789012 then the value of the first digit is
// signified by using odd for '1', even for '2', even for '3', odd for '4',
// odd for '5', and even for '6'. See http://en.wikipedia.org/wiki/EAN-13
//
//                Parity of next 6 digits
//    Digit   0     1     2     3     4     5
//       0    Odd   Odd   Odd   Odd   Odd   Odd
//       1    Odd   Odd   Even  Odd   Even  Even
//       2    Odd   Odd   Even  Even  Odd   Even
//       3    Odd   Odd   Even  Even  Even  Odd
//       4    Odd   Even  Odd   Odd   Even  Even
//       5    Odd   Even  Even  Odd   Odd   Even
//       6    Odd   Even  Even  Even  Odd   Odd
//       7    Odd   Even  Odd   Even  Odd   Even
//       8    Odd   Even  Odd   Even  Even  Odd
//       9    Odd   Even  Even  Odd   Even  Odd
//
// Note that the encoding for '0' uses the same parity as a UPC barcode. Hence
// a UPC barcode can be converted to an EAN-13 barcode by prepending a 0.
//
// The encoding is represented by the following array, which is a bit pattern
// using Odd = 0 and Even = 1. For example, 5 is represented by:
//
//              Odd Even Even Odd Odd Even
// in binary:
//                0    1    1   0   0    1   == 0x19
//
    static final int[] FIRST_DIGIT_ENCODINGS = {
            0x00, 0x0B, 0x0D, 0xE, 0x13, 0x19, 0x1C, 0x15, 0x16, 0x1A
    };

    /**
     * Start/end guard pattern.
     */
    static final int[] START_END_PATTERN = {1, 1, 1,};

    /**
     * As above but also including the "even", or "G" patterns used to encode UPC/EAN digits.
     */
    static final int[][] L_AND_G_PATTERNS;

    /**
     * "Odd", or "L" patterns used to encode UPC/EAN digits.
     */
    static final int[][] L_PATTERNS = {
            {3, 2, 1, 1}, // 0
            {2, 2, 2, 1}, // 1
            {2, 1, 2, 2}, // 2
            {1, 4, 1, 1}, // 3
            {1, 1, 3, 2}, // 4
            {1, 2, 3, 1}, // 5
            {1, 1, 1, 4}, // 6
            {1, 3, 1, 2}, // 7
            {1, 2, 1, 3}, // 8
            {3, 1, 1, 2}  // 9
    };

    /**
     * Pattern marking the middle of a UPC/EAN pattern, separating the two halves.
     */
    static final int[] MIDDLE_PATTERN = {1, 1, 1, 1, 1};

    static {
        L_AND_G_PATTERNS = new int[20][];
        System.arraycopy(L_PATTERNS, 0, L_AND_G_PATTERNS, 0, 10);
        for (int i = 10; i < 20; i++) {
            int[] widths = L_PATTERNS[i - 10];
            int[] reversedWidths = new int[widths.length];
            for (int j = 0; j < widths.length; j++) {
                reversedWidths[j] = widths[widths.length - j - 1];
            }
            L_AND_G_PATTERNS[i] = reversedWidths;
        }
    }

    private static final int CODE_WIDTH = 3 + // start guard
            (7 * 6) + // left bars
            5 + // middle guard
            (7 * 6) + // right bars
            3; // end guard

    private static final int DEFAULT_MARGIN = 10;

    //This list should contain all positions of lines which should be longer than the other lines
    private List<Integer> mLongLinePositions;

    public CustomEAN13Writer() {
        mLongLinePositions = new ArrayList<>();
    }

    /**
     * @param target     encode black/white pattern into this array
     * @param pos        position to start encoding at in {@code target}
     * @param pattern    lengths of black/white runs to encode
     * @param startColor starting color - false for white, true for black
     * @return the number of elements added to target.
     */
    public int appendPatternAndConsiderLongLinePosition(boolean[] target, int pos, int[] pattern,
                                                        boolean startColor) {
        boolean color = startColor;
        int numAdded = 0;
        for (int len : pattern) {
            for (int j = 0; j < len; j++) {
                //If the pattern is the start-, middle- or end-pattern save the position for rendering later
                if (pattern.equals(START_END_PATTERN) || pattern.equals(MIDDLE_PATTERN) || pattern
                        .equals(START_END_PATTERN)) {
                    mLongLinePositions.add(pos);
                }
                target[pos++] = color;
            }
            numAdded += len;
            color = !color; // flip color after each segment
        }
        return numAdded;
    }

    @Override
    public boolean[] encode(final String contents) {
        if (contents.length() != 13) {
            throw new IllegalArgumentException(
                    "Requested contents should be 13 digits long, but got " + contents.length());
        }
        try {
            if (!checkStandardUPCEANChecksum(contents)) {
                throw new IllegalArgumentException("Contents do not pass checksum");
            }
        } catch (FormatException ignored) {
            throw new IllegalArgumentException("Illegal contents");
        }

        int firstDigit = Integer.parseInt(contents.substring(0, 1));
        int parities = FIRST_DIGIT_ENCODINGS[firstDigit];
        boolean[] result = new boolean[CODE_WIDTH];
        int pos = 0;

        pos += appendPatternAndConsiderLongLinePosition(result, pos, START_END_PATTERN, true);

        // See {@link #EAN13Reader} for a description of how the first digit & left bars are encoded
        for (int i = 1; i <= 6; i++) {
            int digit = Integer.parseInt(contents.substring(i, i + 1));
            if ((parities >> (6 - i) & 1) == 1) {
                digit += 10;
            }
            pos += appendPatternAndConsiderLongLinePosition(result, pos, L_AND_G_PATTERNS[digit],
                    false);
        }

        pos += appendPatternAndConsiderLongLinePosition(result, pos, MIDDLE_PATTERN, false);

        for (int i = 7; i <= 12; i++) {
            int digit = Integer.parseInt(contents.substring(i, i + 1));
            pos += appendPatternAndConsiderLongLinePosition(result, pos, L_PATTERNS[digit], true);
        }
        appendPatternAndConsiderLongLinePosition(result, pos, START_END_PATTERN, true);

        return result;
    }

    public BitMatrix encodeAndRender(String contents, int width, int height) {
        boolean[] code = encode(contents);

        int inputWidth = code.length;
        // Add quiet zone on both sides.
        int fullWidth = inputWidth + DEFAULT_MARGIN;
        int outputWidth = Math.max(width, fullWidth);
        int outputHeight = Math.max(1, height);

        int multiple = outputWidth / fullWidth;
        int leftPadding = (outputWidth - (inputWidth * multiple)) / 2;

        BitMatrix output = new BitMatrix(outputWidth, outputHeight);
        for (int inputX = 0, outputX = leftPadding; inputX < inputWidth;
             inputX++, outputX += multiple) {
            if (code[inputX]) {
                int barcodeHeight = outputHeight;
                //if the position isn't in the list for long lines we have to shorten the line by 5%
                if (!mLongLinePositions.contains(inputX)) {
                    barcodeHeight = (int) ((float) outputHeight * 0.95f);
                }
                output.setRegion(outputX, 0, multiple, barcodeHeight);

            }
        }
        return output;
    }

    /**
     * Computes the UPC/EAN checksum on a string of digits, and reports whether the checksum is
     * correct or not.
     *
     * @param s string of digits to check
     * @return true iff string of digits passes the UPC/EAN checksum algorithm
     * @throws FormatException if the string does not contain only digits
     */
    static boolean checkStandardUPCEANChecksum(CharSequence s) throws FormatException {
        int length = s.length();
        if (length == 0) {
            return false;
        }

        int sum = 0;
        for (int i = length - 2; i >= 0; i -= 2) {
            int digit = (int) s.charAt(i) - (int) '0';
            if (digit < 0 || digit > 9) {
                throw FormatException.getFormatInstance();
            }
            sum += digit;
        }
        sum *= 3;
        for (int i = length - 1; i >= 0; i -= 2) {
            int digit = (int) s.charAt(i) - (int) '0';
            if (digit < 0 || digit > 9) {
                throw FormatException.getFormatInstance();
            }
            sum += digit;
        }
        return sum % 10 == 0;
    }
}