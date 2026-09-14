package com.ascendersmc.installer.util;

import java.util.*;

public final class SimpleJson {
    private final String s; private int i;
    private SimpleJson(String s) { this.s = s; }

    public static Object parse(String text) {
        SimpleJson p = new SimpleJson(text); Object value = p.value(); p.ws();
        if (p.i != p.s.length()) throw new IllegalArgumentException("JSON extra en posición " + p.i);
        return value;
    }

    public static String stringify(Object value) { StringBuilder out = new StringBuilder(); write(out, value, 0); return out.toString(); }
    private static void write(StringBuilder out, Object value, int depth) {
        if (value == null) { out.append("null"); return; }
        if (value instanceof String s) { quote(out, s); return; }
        if (value instanceof Number || value instanceof Boolean) { out.append(value); return; }
        if (value instanceof Map<?,?> map) {
            out.append("{\n"); int n = 0;
            for (var e : map.entrySet()) {
                if (n++ > 0) out.append(",\n"); indent(out, depth + 1); quote(out, String.valueOf(e.getKey())); out.append(": "); write(out, e.getValue(), depth + 1);
            }
            if (!map.isEmpty()) { out.append('\n'); indent(out, depth); }
            out.append('}'); return;
        }
        if (value instanceof Iterable<?> it) {
            out.append("["); int n = 0;
            for (Object x : it) { if (n++ > 0) out.append(", "); write(out, x, depth + 1); }
            out.append(']'); return;
        }
        quote(out, String.valueOf(value));
    }
    private static void indent(StringBuilder out, int depth) { out.append("  ".repeat(Math.max(0, depth))); }
    private static void quote(StringBuilder out, String s) {
        out.append('"');
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\""); case '\\' -> out.append("\\\\"); case '\n' -> out.append("\\n"); case '\r' -> out.append("\\r"); case '\t' -> out.append("\\t");
                default -> { if (c < 32) out.append(String.format("\\u%04x", (int)c)); else out.append(c); }
            }
        }
        out.append('"');
    }

    private Object value() { ws(); if (i >= s.length()) throw err("Fin inesperado"); char c=s.charAt(i); return switch(c){case '{'->object();case '['->array();case '"'->string();case 't'->literal("true",Boolean.TRUE);case 'f'->literal("false",Boolean.FALSE);case 'n'->literal("null",null);default->number();}; }
    private Map<String,Object> object(){expect('{');Map<String,Object>m=new LinkedHashMap<>();ws();if(peek('}')){i++;return m;}while(true){ws();String k=string();ws();expect(':');m.put(k,value());ws();if(peek('}')){i++;return m;}expect(',');}}
    private List<Object> array(){expect('[');List<Object>l=new ArrayList<>();ws();if(peek(']')){i++;return l;}while(true){l.add(value());ws();if(peek(']')){i++;return l;}expect(',');}}
    private String string(){expect('"');StringBuilder b=new StringBuilder();while(i<s.length()){char c=s.charAt(i++);if(c=='"')return b.toString();if(c=='\\'){if(i>=s.length())throw err("Escape incompleto");char e=s.charAt(i++);switch(e){case '"','\\','/'->b.append(e);case 'b'->b.append('\b');case 'f'->b.append('\f');case 'n'->b.append('\n');case 'r'->b.append('\r');case 't'->b.append('\t');case 'u'->{if(i+4>s.length())throw err("Unicode incompleto");b.append((char)Integer.parseInt(s.substring(i,i+4),16));i+=4;}default->throw err("Escape inválido: "+e);}}else b.append(c);}throw err("String sin cerrar");}
    private Object number(){
        int start=i;
        if(peek('-'))i++;
        while(i<s.length()&&Character.isDigit(s.charAt(i)))i++;
        boolean decimal=false;
        if(peek('.')){
            decimal=true;
            i++;
            while(i<s.length()&&Character.isDigit(s.charAt(i)))i++;
        }
        if(i<s.length()&&(s.charAt(i)=='e'||s.charAt(i)=='E')){
            decimal=true;
            i++;
            if(i<s.length()&&(s.charAt(i)=='+'||s.charAt(i)=='-'))i++;
            while(i<s.length()&&Character.isDigit(s.charAt(i)))i++;
        }
        if(start==i)throw err("Valor inválido");
        String n=s.substring(start,i);
        // No usar un ternario entre Double.parseDouble y Long.parseLong: la
        // promoción numérica de Java convierte ambas ramas a double y termina
        // devolviendo Double incluso para enteros JSON (432 -> 432.0).
        if(decimal)return Double.valueOf(n);
        return Long.valueOf(n);
    }
    private Object literal(String expected,Object value){if(!s.startsWith(expected,i))throw err("Literal inválido");i+=expected.length();return value;}
    private void ws(){while(i<s.length()&&Character.isWhitespace(s.charAt(i)))i++;}private boolean peek(char c){return i<s.length()&&s.charAt(i)==c;}private void expect(char c){ws();if(!peek(c))throw err("Se esperaba '"+c+"'");i++;}private IllegalArgumentException err(String m){return new IllegalArgumentException(m+" en posición "+i);}
}
