-- Aula em slides: as questoes de fixacao no meio da aula ficam num quiz embutido nela (respostas contam no desempenho).
ALTER TABLE quiz ADD COLUMN embedded boolean NOT NULL DEFAULT 0;
