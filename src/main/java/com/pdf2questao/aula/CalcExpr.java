package com.pdf2questao.aula;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Expressoes dos exercicios guiados (```calculo): aritmetica com variaveis, sem nada executavel. A MESMA gramatica
 * esta em static/js/calc-expr.js (o navegador sorteia e confere; o servidor valida o gerador na criacao da aula).
 *
 * <pre>
 * expr  := or ;  or := and ('||' and)* ;  and := cmp ('&&' cmp)*
 * cmp   := add (('=='|'!='|'<='|'>='|'<'|'>') add)?
 * add   := mul (('+'|'-') mul)* ;  mul := unary (('*'|'/'|'%') unary)*
 * unary := ('-'|'!') unary | pow ;  pow := primary ('^' unary)?
 * primary := numero | nome | nome '(' expr (',' expr)* ')' | '(' expr ')'
 * funcoes: ln, log10, exp, sqrt, abs, floor, ceil, round(x[,casas]), min, max, pow
 * </pre>
 * Verdadeiro = 1, falso = 0.
 */
public final class CalcExpr {

    private final String src;
    private int pos;

    private CalcExpr(String src) {
        this.src = src;
    }

    /** Valor da expressao com as variaveis dadas; erro de sintaxe ou variavel desconhecida = IllegalArgumentException. */
    public static double eval(String expression, Map<String, Double> vars) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("expressão vazia");
        }
        CalcExpr p = new CalcExpr(expression);
        double v = p.or(vars);
        p.skip();
        if (p.pos < p.src.length()) {
            throw new IllegalArgumentException("\"" + expression + "\": sobra \"" + p.src.substring(p.pos) + "\"");
        }
        return v;
    }

    /** Mesmo arredondamento do JS: meio para cima. */
    static double round(double x, int places) {
        double f = Math.pow(10, places);
        return Math.floor(x * f + 0.5) / f;
    }

    private void skip() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }

    private boolean eat(String token) {
        skip();
        if (src.startsWith(token, pos)) {
            pos += token.length();
            return true;
        }
        return false;
    }

    private double or(Map<String, Double> v) {
        double a = and(v);
        while (eat("||")) {
            double b = and(v);
            a = (a != 0 || b != 0) ? 1 : 0;
        }
        return a;
    }

    private double and(Map<String, Double> v) {
        double a = cmp(v);
        while (eat("&&")) {
            double b = cmp(v);
            a = (a != 0 && b != 0) ? 1 : 0;
        }
        return a;
    }

    private double cmp(Map<String, Double> v) {
        double a = add(v);
        for (String op : new String[]{"==", "!=", "<=", ">=", "<", ">"}) {
            if (eat(op)) {
                double b = add(v);
                boolean r = switch (op) {
                    case "==" -> Math.abs(a - b) < 1e-9;
                    case "!=" -> Math.abs(a - b) >= 1e-9;
                    case "<=" -> a <= b;
                    case ">=" -> a >= b;
                    case "<" -> a < b;
                    default -> a > b;
                };
                return r ? 1 : 0;
            }
        }
        return a;
    }

    private double add(Map<String, Double> v) {
        double a = mul(v);
        while (true) {
            if (eat("+")) {
                a += mul(v);
            } else if (peekMinus()) {
                pos++;
                a -= mul(v);
            } else {
                return a;
            }
        }
    }

    private boolean peekMinus() {
        skip();
        return pos < src.length() && src.charAt(pos) == '-';
    }

    private double mul(Map<String, Double> v) {
        double a = unary(v);
        while (true) {
            if (eat("*")) {
                a *= unary(v);
            } else if (eat("/")) {
                a /= unary(v);
            } else if (eat("%")) {
                a %= unary(v);
            } else {
                return a;
            }
        }
    }

    private double unary(Map<String, Double> v) {
        skip();
        if (pos < src.length() && src.charAt(pos) == '-') {
            pos++;
            return -unary(v);
        }
        if (pos < src.length() && src.charAt(pos) == '!' && !src.startsWith("!=", pos)) {
            pos++;
            return unary(v) == 0 ? 1 : 0;
        }
        return pow(v);
    }

    private double pow(Map<String, Double> v) {
        double base = primary(v);
        if (eat("^")) {
            return Math.pow(base, unary(v));
        }
        return base;
    }

    private double primary(Map<String, Double> v) {
        skip();
        if (pos >= src.length()) {
            throw new IllegalArgumentException("\"" + src + "\": termina no meio");
        }
        char c = src.charAt(pos);
        if (eat("(")) {
            double x = or(v);
            if (!eat(")")) {
                throw new IllegalArgumentException("\"" + src + "\": falta \")\"");
            }
            return x;
        }
        if (Character.isDigit(c) || c == '.') {
            int start = pos;
            while (pos < src.length() && (Character.isDigit(src.charAt(pos)) || src.charAt(pos) == '.')) {
                pos++;
            }
            if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
                int save = pos;
                pos++;
                if (pos < src.length() && (src.charAt(pos) == '-' || src.charAt(pos) == '+')) {
                    pos++;
                }
                if (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                    while (pos < src.length() && Character.isDigit(src.charAt(pos))) {
                        pos++;
                    }
                } else {
                    pos = save;
                }
            }
            return Double.parseDouble(src.substring(start, pos));
        }
        if (Character.isLetter(c) || c == '_') {
            int start = pos;
            while (pos < src.length() && (Character.isLetterOrDigit(src.charAt(pos)) || src.charAt(pos) == '_')) {
                pos++;
            }
            String name = src.substring(start, pos);
            if (eat("(")) {
                List<Double> args = new ArrayList<>();
                if (!eat(")")) {
                    do {
                        args.add(or(v));
                    } while (eat(","));
                    if (!eat(")")) {
                        throw new IllegalArgumentException("\"" + src + "\": falta \")\" em " + name + "(");
                    }
                }
                return call(name, args);
            }
            Double value = v.get(name);
            if (value == null) {
                throw new IllegalArgumentException("variável desconhecida \"" + name + "\" em \"" + src + "\"");
            }
            return value;
        }
        throw new IllegalArgumentException("\"" + src + "\": caractere inesperado \"" + c + "\"");
    }

    private double call(String name, List<Double> a) {
        int n = a.size();
        java.util.function.IntPredicate arity = k -> n == k;
        switch (name) {
            case "ln": case "log10": case "exp": case "sqrt": case "abs": case "floor": case "ceil":
                if (!arity.test(1)) {
                    break;
                }
                double x = a.get(0);
                return switch (name) {
                    case "ln" -> Math.log(x);
                    case "log10" -> Math.log10(x);
                    case "exp" -> Math.exp(x);
                    case "sqrt" -> Math.sqrt(x);
                    case "abs" -> Math.abs(x);
                    case "floor" -> Math.floor(x);
                    default -> Math.ceil(x);
                };
            case "round":
                if (n == 1 || n == 2) {
                    return round(a.get(0), n == 2 ? (int) Math.round(a.get(1)) : 0);
                }
                break;
            case "min":
                if (n >= 1) {
                    return a.stream().mapToDouble(Double::doubleValue).min().getAsDouble();
                }
                break;
            case "max":
                if (n >= 1) {
                    return a.stream().mapToDouble(Double::doubleValue).max().getAsDouble();
                }
                break;
            case "pow":
                if (n == 2) {
                    return Math.pow(a.get(0), a.get(1));
                }
                break;
            default:
                throw new IllegalArgumentException("função desconhecida \"" + name + "\" (use ln, log10, exp, sqrt, "
                        + "abs, floor, ceil, round, min, max, pow)");
        }
        throw new IllegalArgumentException("número de argumentos errado em " + name + "()");
    }
}
