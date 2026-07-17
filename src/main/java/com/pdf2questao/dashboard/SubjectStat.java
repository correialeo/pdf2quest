package com.pdf2questao.dashboard;

public record SubjectStat(String label, long correct, long total) {
    public double percent() {
        return total == 0 ? 0 : (correct * 100.0) / total;
    }
}
