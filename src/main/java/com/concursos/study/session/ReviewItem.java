package com.concursos.study.session;

import com.concursos.study.question.Question;

public record ReviewItem(int index, Question question, String selectedAnswer, Boolean correct) {
}
