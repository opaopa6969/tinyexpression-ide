package org.unlaxer.tinyexpression.ide;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

/**
 * Minimal expression evaluator for the MVP.
 * <p>
 * Supports: +, -, *, /, parentheses, unary minus, decimal numbers.
 * <p>
 * This is a stopgap until the full tinyexpression evaluator
 * (AstEvaluatorCalculator) is wired in as a dependency.
 * <p>
 * Grammar (recursive descent):
 * <pre>
 *   expr    = term (('+' | '-') term)*
 *   term    = unary (('*' | '/') unary)*
 *   unary   = '-' unary | primary
 *   primary = NUMBER | '(' expr ')'
 * </pre>
 */
public final class SimpleExpressionEvaluator {

    static final int MAX_NESTING_DEPTH = 1000;

    private SimpleExpressionEvaluator() {}

    public static Object evaluate(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("Empty expression");
        }
        Parser parser = new Parser(expression.trim());
        BigDecimal result = parser.parseExpression();
        if (parser.pos < parser.input.length()) {
            throw new IllegalArgumentException(
                "Unexpected character at position " + parser.pos + ": '" + parser.input.charAt(parser.pos) + "'");
        }
        // Return integer if no fractional part
        if (result.stripTrailingZeros().scale() <= 0) {
            try {
                return result.longValueExact();
            } catch (ArithmeticException e) {
                return result;
            }
        }
        return result;
    }

    private static class Parser {
        final String input;
        int pos;
        int nestingDepth;

        Parser(String input) {
            this.input = input;
            this.pos = 0;
        }

        void skipWhitespace() {
            while (pos < input.length() && Character.isWhitespace(input.charAt(pos))) {
                pos++;
            }
        }

        BigDecimal parseExpression() {
            BigDecimal left = parseTerm();
            skipWhitespace();
            while (pos < input.length()) {
                char op = input.charAt(pos);
                if (op != '+' && op != '-') break;
                pos++;
                BigDecimal right = parseTerm();
                left = (op == '+') ? left.add(right) : left.subtract(right);
                skipWhitespace();
            }
            return left;
        }

        BigDecimal parseTerm() {
            BigDecimal left = parseUnary();
            skipWhitespace();
            while (pos < input.length()) {
                char op = input.charAt(pos);
                if (op != '*' && op != '/') break;
                pos++;
                BigDecimal right = parseUnary();
                if (op == '*') {
                    left = left.multiply(right);
                } else {
                    if (right.compareTo(BigDecimal.ZERO) == 0) {
                        throw new ArithmeticException("Division by zero");
                    }
                    left = left.divide(right, MathContext.DECIMAL128);
                }
                skipWhitespace();
            }
            return left;
        }

        BigDecimal parseUnary() {
            skipWhitespace();
            int negations = 0;
            while (pos < input.length() && input.charAt(pos) == '-') {
                pos++;
                negations++;
                skipWhitespace();
            }
            BigDecimal result = parsePrimary();
            return (negations % 2 == 0) ? result : result.negate();
        }

        BigDecimal parsePrimary() {
            skipWhitespace();
            if (pos >= input.length()) {
                throw new IllegalArgumentException("Unexpected end of expression");
            }

            char c = input.charAt(pos);

            // Parenthesized expression
            if (c == '(') {
                if (++nestingDepth > MAX_NESTING_DEPTH) {
                    nestingDepth--;
                    throw new IllegalArgumentException(
                            "Expression nesting depth exceeds maximum of " + MAX_NESTING_DEPTH);
                }
                pos++;
                try {
                    BigDecimal result = parseExpression();
                    skipWhitespace();
                    if (pos >= input.length() || input.charAt(pos) != ')') {
                        throw new IllegalArgumentException("Missing closing parenthesis");
                    }
                    pos++;
                    return result;
                } finally {
                    nestingDepth--;
                }
            }

            // Number
            if (Character.isDigit(c) || c == '.') {
                int start = pos;
                while (pos < input.length() && (Character.isDigit(input.charAt(pos)) || input.charAt(pos) == '.')) {
                    pos++;
                }
                String numStr = input.substring(start, pos);
                try {
                    return new BigDecimal(numStr);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid number: " + numStr);
                }
            }

            // Boolean literals
            if (input.startsWith("true", pos)) {
                pos += 4;
                return BigDecimal.ONE;
            }
            if (input.startsWith("false", pos)) {
                pos += 5;
                return BigDecimal.ZERO;
            }

            throw new IllegalArgumentException(
                "Unexpected character at position " + pos + ": '" + c + "'");
        }
    }
}
