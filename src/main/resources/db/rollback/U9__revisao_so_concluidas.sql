-- V9 so corrige dados; para voltar ao estado anterior, restaure o backup do banco. Aqui so libera a versao.
DELETE FROM flyway_schema_history WHERE version = '9';
