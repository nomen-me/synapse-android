// Testa o código do nó "Monta resposta" da PROPOSTA com um contexto n8n simulado e relógio fixo (14/10/2026, quarta).
const fs = require('fs'), assert = require('assert');
const wf = JSON.parse(fs.readFileSync(__dirname + '/06_consumo_servicos.proposta.json', 'utf8'));
const codigo = wf.nodes.find(n => n.name === 'Monta resposta').parameters.jsCode;
const FIXO = Date.UTC(2026, 9, 14, 15, 0, 0);
const RealDate = Date;
class FakeDate extends RealDate { constructor(...a) { if (a.length === 0) super(FIXO); else super(...a); } static now() { return FIXO; } }
function rodar(credData, entrada) {
  globalThis.Date = FakeDate;
  try {
    const f = new Function('$', '$input', codigo);
    const r = f(() => ({ first: () => ({ json: { data: credData } }) }), { first: () => ({ json: entrada }) });
    return r[0].json;
  } finally { globalThis.Date = RealDate; }
}
const melhor = [{ provedor: 'melhorenvio', status: 'conectado', saldo: 'R$ 20,00' }];
// semana corrente começa na segunda 12/10; 5 semanas: 15/09, 22/09, 29/09, 06/10, 13/10 -> ver rótulos
let r = rodar(melhor, { data: [
  { creation: '2026-10-13 09:00:00.1' }, { creation: '2026-10-14 10:00:00' },            // semana atual (2) ; mês (2)
  { creation: '2026-10-05 08:00:00' },                                                    // semana de 05/10 (1); mês
  { creation: '2026-09-16 12:00:00' },                                                    // 1ª semana (1); fora do mês
  { creation: '2026-09-01 12:00:00' }, { creation: 'lixo' } ] });
assert.strictEqual(r.status, 200); assert.strictEqual(r.body.sucesso, true);
assert.deepStrictEqual(r.body.ia, []);                                                    // IA nunca inventada
assert.strictEqual(r.body.frete.length, 5);
assert.deepStrictEqual(r.body.frete.map(x => x.v), [1, 0, 0, 1, 2]);                      // 14/09? ver rótulos abaixo
assert.strictEqual(r.body.freteVolume, '3');                                              // 13/10, 14/10, 05/10 (mês corrente)
assert.strictEqual(r.body.freteSaldo, 'R$ 20,00');
assert.ok(r.body.pendencias.some(p => p.campo === 'ia') && r.body.pendencias.length === 1);
// consulta de frete falhou, mas há saldo: 200 parcial com pendência explícita de frete
r = rodar(melhor, { error: { message: 'Field not permitted in query: custom_tracking_code' } });
assert.strictEqual(r.status, 200); assert.deepStrictEqual(r.body.frete, []); assert.strictEqual(r.body.freteVolume, '—');
assert.ok(r.body.pendencias.some(p => p.campo === 'frete' && /custom_tracking_code/.test(p.detalhe)));
// nada disponível: mantém o 501 do contrato atual
r = rodar([], { error: { message: 'x' } });
assert.strictEqual(r.status, 501); assert.strictEqual(r.body.status, 'PENDENTE_FONTE_DE_DADOS');
// frete ok, sem Melhor Envio conectado: 200, saldo pendente
r = rodar([], { data: [] });
assert.strictEqual(r.status, 200); assert.strictEqual(r.body.freteSaldo, '—'); assert.ok(r.body.pendencias.some(p => p.campo === 'freteSaldo'));
console.log('OK: 4 cenários da proposta do nó "Monta resposta" (simulação; não é o n8n real)');
