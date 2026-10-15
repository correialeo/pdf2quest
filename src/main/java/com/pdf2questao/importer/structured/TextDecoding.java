package com.pdf2questao.importer.structured;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Arquivos de texto enviados pelo usuario: UTF-8 (com ou sem BOM) ou, se nao for UTF-8, Windows-1252 (Excel). */
final class TextDecoding {

    private TextDecoding() {
    }

    static String decode(byte[] bytes) {
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            text = new String(bytes, Charset.forName("windows-1252"));
        }
        return text.startsWith("﻿") ? text.substring(1) : text;
    }
}
