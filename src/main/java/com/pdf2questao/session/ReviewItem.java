package com.pdf2questao.session;

import com.pdf2questao.question.Question;

public record ReviewItem(int index, Question question, String selectedAnswer, Boolean correct) {
}
