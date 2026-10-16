const cred = ($('Ritmo — Credenciais de envio').first().json.data) || [];
const fr = $input.first().json;
const me = cred.find(r => r.provedor === 'melhorenvio' && r.status === 'conectado') || null;
const DIA = 86400000;
const hoje = Date.UTC(new Date().getUTCFullYear(), new Date().getUTCMonth(), new Date().getUTCDate());
const semanaAtual = hoje - ((new Date(hoje).getUTCDay() + 6) % 7) * DIA;          // segunda-feira da semana corrente (UTC)
const inicios = [4, 3, 2, 1, 0].map(i => semanaAtual - i * 7 * DIA);               // últimas 5 semanas
const iniMes = Date.UTC(new Date().getUTCFullYear(), new Date().getUTCMonth(), 1);
const pendencias = [{ campo: 'ia', codigo: 'PENDENTE_FONTE_DE_DADOS', detalhe: 'Sem fonte de série diária de requisições de IA: a Lyra Central só tem (a confirmar) total mensal por tenant. Nenhum valor é inventado.' }];
let frete = [], freteVolume = '—';
if (Array.isArray(fr.data)) {
  const baldes = inicios.map(() => 0); let noMes = 0;
  for (const r of fr.data) {
    const p = String(r.creation || '').slice(0, 10).split('-').map(Number);
    if (p.length !== 3 || p.some(isNaN)) continue;
    const t = Date.UTC(p[0], p[1] - 1, p[2]);
    const i = Math.floor((t - inicios[0]) / (7 * DIA));
    if (i >= 0 && i < baldes.length) baldes[i]++;
    if (t >= iniMes) noMes++;
  }
  const rot = t => { const x = new Date(t); return String(x.getUTCDate()).padStart(2, '0') + '/' + String(x.getUTCMonth() + 1).padStart(2, '0'); };
  frete = inicios.map((t, i) => ({ d: 'Sem ' + rot(t), v: baldes[i] }));
  freteVolume = String(noMes);
} else {
  pendencias.push({ campo: 'frete', codigo: 'PENDENTE_FONTE_DE_DADOS', detalhe: 'Consulta de Sales Invoice.custom_tracking_code falhou (o campo existe no Ritmo? W2 está gravando?): ' + String((fr.error && fr.error.message) || fr.message || 'sem detalhe') });
}
let freteSaldo = '—';
if (me && me.saldo) freteSaldo = me.saldo;
else pendencias.push({ campo: 'freteSaldo', codigo: 'PENDENTE_FONTE_DE_DADOS', detalhe: me ? 'Cache de saldo do Melhor Envio vazio no Ritmo.' : 'Melhor Envio não está conectado no Ritmo.' });
const tudoPendente = !Array.isArray(fr.data) && !(me && me.saldo);
if (tudoPendente) {
  const m = 'PENDENTE_FONTE_DE_DADOS: ' + pendencias.map(p => p.campo).join(', ');
  return [{ json: { status: 501, body: { sucesso: false, status: 'PENDENTE_FONTE_DE_DADOS', erro: m, message: m, pendencias } } }];
}
return [{ json: { status: 200, body: { sucesso: true, ia: [], frete, freteSaldo, freteVolume, pendencias } } }];
