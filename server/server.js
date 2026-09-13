import express from "express";
import cors from "cors";
import helmet from "helmet";
import rateLimit from "express-rate-limit";
import bcrypt from "bcryptjs";
import jwt from "jsonwebtoken";
import fs from "fs";
import path from "path";
import crypto from "crypto";
import OpenAI from "openai";
import pg from "pg";

const { Pool } = pg;
const app = express();
const PORT = Number(process.env.PORT || 8080);
const JWT_SECRET = process.env.JWT_SECRET || "CHANGE_ME_IN_PRODUCTION";
const DATA_DIR = process.env.DATA_DIR || "./data";
const DB_FILE = path.join(DATA_DIR, "sellsan.json");
const MODEL = process.env.OPENAI_MODEL || "gpt-5.6-luna";
const pool = process.env.DATABASE_URL ? new Pool({ connectionString: process.env.DATABASE_URL, ssl: process.env.DATABASE_SSL === "false" ? false : { rejectUnauthorized: false } }) : null;
fs.mkdirSync(DATA_DIR, { recursive: true });

app.use(helmet());
app.use(cors({ origin: process.env.CORS_ORIGIN || "*" }));
app.use(express.json({ limit: "1mb" }));
app.use(rateLimit({ windowMs: 60_000, limit: 120, standardHeaders: true, legacyHeaders: false }));

function cleanSnapshot(body = {}) {
  return {
    clients: Array.isArray(body.clients) ? body.clients.slice(0, 5000) : [],
    quotes: Array.isArray(body.quotes) ? body.quotes.slice(0, 5000) : [],
    agenda: Array.isArray(body.agenda) ? body.agenda.slice(0, 5000) : [],
    company: String(body.company || "").slice(0, 120),
    seller: String(body.seller || "").slice(0, 120),
    updatedAt: Date.now()
  };
}

function loadJsonDb() { try { return JSON.parse(fs.readFileSync(DB_FILE, "utf8")); } catch { return { users: [], snapshots: {} }; } }
function saveJsonDb(db) { const tmp = DB_FILE + ".tmp"; fs.writeFileSync(tmp, JSON.stringify(db, null, 2)); fs.renameSync(tmp, DB_FILE); }

async function initDb() {
  if (!pool) return;
  await pool.query(`CREATE TABLE IF NOT EXISTS users (id TEXT PRIMARY KEY, company TEXT NOT NULL, name TEXT NOT NULL, email TEXT UNIQUE NOT NULL, password_hash TEXT NOT NULL, created_at BIGINT NOT NULL)`);
  await pool.query(`CREATE TABLE IF NOT EXISTS snapshots (user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE, data JSONB NOT NULL, updated_at BIGINT NOT NULL)`);
}
async function findUserByEmail(email) {
  if (pool) return (await pool.query("SELECT id, company, name, email, password_hash AS \"passwordHash\" FROM users WHERE email=$1", [email])).rows[0] || null;
  return loadJsonDb().users.find(u => u.email === email) || null;
}
async function createUser(user, snapshot) {
  if (pool) {
    await pool.query("INSERT INTO users(id,company,name,email,password_hash,created_at) VALUES($1,$2,$3,$4,$5,$6)",[user.id,user.company,user.name,user.email,user.passwordHash,user.createdAt]);
    await pool.query("INSERT INTO snapshots(user_id,data,updated_at) VALUES($1,$2::jsonb,$3)",[user.id,JSON.stringify(snapshot),Date.now()]);
  } else { const db=loadJsonDb();db.users.push(user);db.snapshots[user.id]=snapshot;saveJsonDb(db); }
}
async function getSnapshot(userId) {
  if (pool) return (await pool.query("SELECT data FROM snapshots WHERE user_id=$1",[userId])).rows[0]?.data || cleanSnapshot({});
  return loadJsonDb().snapshots[userId] || cleanSnapshot({});
}
async function putSnapshot(userId, snapshot) {
  const clean=cleanSnapshot(snapshot);
  if (pool) await pool.query("INSERT INTO snapshots(user_id,data,updated_at) VALUES($1,$2::jsonb,$3) ON CONFLICT(user_id) DO UPDATE SET data=EXCLUDED.data, updated_at=EXCLUDED.updated_at",[userId,JSON.stringify(clean),Date.now()]);
  else { const db=loadJsonDb();db.snapshots[userId]=clean;saveJsonDb(db); }
  return clean;
}
function tokenFor(user) { return jwt.sign({ sub: user.id, email: user.email }, JWT_SECRET, { expiresIn: "30d" }); }
function auth(req, res, next) { const raw=req.headers.authorization||"";const token=raw.startsWith("Bearer ")?raw.slice(7):"";try{req.user=jwt.verify(token,JWT_SECRET);next();}catch{res.status(401).json({error:"Sessão inválida. Entre novamente."});} }

app.get("/api/health", async (_req,res)=>{let db="json-local";try{if(pool){await pool.query("SELECT 1");db="postgres";}}catch{db="postgres-error";}res.json({ok:true,service:"SellSan Server",version:"4.0.0",database:db,ai:Boolean(process.env.OPENAI_API_KEY)});});

app.post("/api/auth/register", async (req,res)=>{
  try{
    const {company,name,email,password}=req.body||{};if(!company||!name||!email||String(password||"").length<6)return res.status(400).json({error:"Dados inválidos."});
    const normalized=String(email).trim().toLowerCase();if(await findUserByEmail(normalized))return res.status(409).json({error:"Este e-mail já está cadastrado."});
    const user={id:crypto.randomUUID(),company:String(company),name:String(name),email:normalized,passwordHash:await bcrypt.hash(String(password),12),createdAt:Date.now()};
    await createUser(user,cleanSnapshot({company,seller:name}));res.json({token:tokenFor(user),user:{id:user.id,email:user.email,name:user.name,company:user.company}});
  }catch(e){console.error(e);res.status(500).json({error:"Não foi possível criar a conta."});}
});
app.post("/api/auth/login", async (req,res)=>{
  const normalized=String(req.body?.email||"").trim().toLowerCase();const user=await findUserByEmail(normalized);if(!user||!(await bcrypt.compare(String(req.body?.password||""),user.passwordHash)))return res.status(401).json({error:"E-mail ou senha inválidos."});
  res.json({token:tokenFor(user),user:{id:user.id,email:user.email,name:user.name,company:user.company}});
});
app.get("/api/sync",auth,async(req,res)=>res.json(await getSnapshot(req.user.sub)));
app.put("/api/sync",auth,async(req,res)=>{const snapshot=await putSnapshot(req.user.sub,req.body);res.json({ok:true,updatedAt:snapshot.updatedAt});});

app.post("/api/ai",auth,async(req,res)=>{
  if(!process.env.OPENAI_API_KEY)return res.status(503).json({error:"OPENAI_API_KEY não configurada no servidor."});
  const prompt=String(req.body?.prompt||"").slice(0,5000);if(!prompt)return res.status(400).json({error:"Pergunta vazia."});
  const context=req.body?.businessContext||await getSnapshot(req.user.sub);const compact={company:context.company,seller:context.seller,clients:(context.clients||[]).slice(-100),quotes:(context.quotes||[]).slice(-150),agenda:(context.agenda||[]).slice(-100)};
  try{
    const client=new OpenAI({apiKey:process.env.OPENAI_API_KEY});
    const response=await client.responses.create({model:MODEL,store:false,input:[{role:"system",content:[{type:"input_text",text:"Você é SellSan IA, assistente comercial para pequenos prestadores de serviços. Responda em português do Brasil, de forma prática, ética e objetiva. Use apenas os dados fornecidos. Nunca invente preços, pagamentos, clientes ou compromissos. Quando sugerir follow-up, seja educado e não gere spam. Priorize ações concretas para converter oportunidades e organizar atendimento."}]},{role:"user",content:[{type:"input_text",text:`Pergunta: ${prompt}\n\nContexto comercial JSON:\n${JSON.stringify(compact)}`}]}]});
    res.json({answer:response.output_text||"Não consegui gerar uma resposta agora."});
  }catch(e){console.error(e);res.status(502).json({error:"Falha ao consultar a IA. Verifique chave, créditos e modelo."});}
});



// ===== SellSan V5 • WhatsApp Business Platform multiempresa + Embedded Signup =====
const META_VERIFY_TOKEN = process.env.META_VERIFY_TOKEN || "";
const META_APP_ID = process.env.META_APP_ID || "";
const META_APP_SECRET = process.env.META_APP_SECRET || "";
const META_CONFIG_ID = process.env.META_CONFIG_ID || "";
const META_GRAPH_VERSION = process.env.META_GRAPH_VERSION || "v23.0";
const PUBLIC_BASE_URL = String(process.env.PUBLIC_BASE_URL || "").replace(/\/$/, "");
const TOKEN_KEY = crypto.createHash("sha256").update(String(process.env.WHATSAPP_TOKEN_ENCRYPTION_KEY || JWT_SECRET)).digest();

function encryptSecret(text){
  const iv=crypto.randomBytes(12);const c=crypto.createCipheriv("aes-256-gcm",TOKEN_KEY,iv);const data=Buffer.concat([c.update(String(text),"utf8"),c.final()]);const tag=c.getAuthTag();return Buffer.concat([iv,tag,data]).toString("base64");
}
function decryptSecret(blob){
  const b=Buffer.from(String(blob),"base64"),iv=b.subarray(0,12),tag=b.subarray(12,28),data=b.subarray(28);const d=crypto.createDecipheriv("aes-256-gcm",TOKEN_KEY,iv);d.setAuthTag(tag);return Buffer.concat([d.update(data),d.final()]).toString("utf8");
}

async function initWhatsappDb(){
  if(!pool) return;
  await pool.query(`CREATE TABLE IF NOT EXISTS services (id TEXT PRIMARY KEY, user_id TEXT REFERENCES users(id) ON DELETE CASCADE, name TEXT NOT NULL, keywords TEXT NOT NULL DEFAULT '', price NUMERIC(12,2) NOT NULL, unit TEXT NOT NULL DEFAULT 'serviço', active BOOLEAN NOT NULL DEFAULT TRUE)`);
  await pool.query(`CREATE TABLE IF NOT EXISTS whatsapp_connections (user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE, waba_id TEXT, phone_number_id TEXT UNIQUE NOT NULL, display_phone TEXT, token_enc TEXT NOT NULL, mode TEXT NOT NULL DEFAULT 'assist', connected_at BIGINT NOT NULL, updated_at BIGINT NOT NULL)`);
  await pool.query(`CREATE TABLE IF NOT EXISTS whatsapp_messages (id TEXT PRIMARY KEY, user_id TEXT REFERENCES users(id) ON DELETE CASCADE, wa_id TEXT NOT NULL, direction TEXT NOT NULL, body TEXT NOT NULL, created_at BIGINT NOT NULL, status TEXT NOT NULL DEFAULT 'received')`);
  await pool.query(`ALTER TABLE whatsapp_messages ADD COLUMN IF NOT EXISTS user_id TEXT REFERENCES users(id) ON DELETE CASCADE`);
}

async function getConnectionByUser(userId){if(!pool)return null;return (await pool.query(`SELECT user_id AS "userId",waba_id AS "wabaId",phone_number_id AS "phoneNumberId",display_phone AS "displayPhone",token_enc AS "tokenEnc",mode FROM whatsapp_connections WHERE user_id=$1`,[userId])).rows[0]||null;}
async function getConnectionByPhoneId(phoneId){if(!pool)return null;return (await pool.query(`SELECT user_id AS "userId",waba_id AS "wabaId",phone_number_id AS "phoneNumberId",display_phone AS "displayPhone",token_enc AS "tokenEnc",mode FROM whatsapp_connections WHERE phone_number_id=$1`,[phoneId])).rows[0]||null;}

async function sendWhatsAppText(conn,to,body){
  if(!conn?.tokenEnc||!conn?.phoneNumberId) throw new Error("WhatsApp não conectado");
  const token=decryptSecret(conn.tokenEnc);
  const r=await fetch(`https://graph.facebook.com/${META_GRAPH_VERSION}/${conn.phoneNumberId}/messages`,{method:"POST",headers:{Authorization:`Bearer ${token}`,"Content-Type":"application/json"},body:JSON.stringify({messaging_product:"whatsapp",to,type:"text",text:{preview_url:false,body:String(body).slice(0,4000)}})});
  const j=await r.json();if(!r.ok)throw new Error(j?.error?.message||"Falha ao enviar WhatsApp");return j;
}
async function saveWaMessage(userId,id,waId,direction,body,status="received"){
  if(pool)await pool.query(`INSERT INTO whatsapp_messages(id,user_id,wa_id,direction,body,created_at,status) VALUES($1,$2,$3,$4,$5,$6,$7) ON CONFLICT(id) DO NOTHING`,[id,userId,waId,direction,body,Date.now(),status]);
}
async function catalogMatch(userId,text){
  if(!pool)return null;const rows=(await pool.query(`SELECT id,name,keywords,price,unit FROM services WHERE user_id=$1 AND active=TRUE ORDER BY name`,[userId])).rows;const t=String(text).toLowerCase();let best=null,score=0;
  for(const x of rows){const words=[x.name,...String(x.keywords||"").split(",")].map(v=>v.trim().toLowerCase()).filter(Boolean);const sc=words.filter(w=>t.includes(w)).length;if(sc>score){score=sc;best=x}}return best;
}
async function addWhatsappOpportunity(userId,waId,service){
  if(!pool||!service)return;const snap=await getSnapshot(userId);const quotes=Array.isArray(snap.quotes)?snap.quotes:[];const exists=quotes.find(q=>q.source==="WhatsApp"&&q.phone===waId&&q.service===service.name&&!['Fechado','Perdido'].includes(q.status));
  if(!exists)quotes.push({id:crypto.randomUUID(),client:waId,phone:waId,service:service.name,value:Number(service.price),details:"Gerado automaticamente pelo atendimento WhatsApp",status:"Orçamento",created:new Date().toLocaleString("pt-BR"),source:"WhatsApp"});
  await putSnapshot(userId,{...snap,quotes});
}
async function markWhatsappApproved(userId,waId){
  if(!pool)return false;const snap=await getSnapshot(userId),quotes=Array.isArray(snap.quotes)?snap.quotes:[];for(let i=quotes.length-1;i>=0;i--){if(quotes[i].phone===waId&&!['Fechado','Perdido'].includes(quotes[i].status)){quotes[i].status='Fechado';await putSnapshot(userId,{...snap,quotes});return true}}return false;
}
function looksApproved(text){return /\b(sim|pode|aprovad[oa]|fechado|vamos fazer|aceito|pode agendar|quero)\b/i.test(String(text));}

async function automaticWhatsappReply(userId,waId,text){
  if(looksApproved(text)&&await markWhatsappApproved(userId,waId))return "Perfeito! ✅ Marquei seu orçamento como aprovado. Qual é o melhor dia e horário para o atendimento? Se preferir, um atendente pode assumir a conversa.";
  const service=await catalogMatch(userId,text);
  if(service){await addWhatsappOpportunity(userId,waId,service);return `Olá! 👋 Encontrei o serviço “${service.name}” no nosso catálogo. Valor base: R$ ${Number(service.price).toFixed(2).replace('.',',')} (${service.unit}). Para confirmar o orçamento, me informe seu nome e os detalhes/quantidade do serviço. Se estiver de acordo com esse valor base, responda “pode agendar”.`;}
  if(process.env.OPENAI_API_KEY){
    const client=new OpenAI({apiKey:process.env.OPENAI_API_KEY});const response=await client.responses.create({model:MODEL,store:false,input:`Você é o atendente SellSan no WhatsApp. Cliente: ${JSON.stringify(String(text).slice(0,1500))}. Responda em português do Brasil, cordial e curto. Não invente preço, desconto, prazo, agenda ou serviço. Se ele pedir orçamento, peça só os dados essenciais para identificar o serviço. Explique que o preço será consultado no catálogo da empresa. Ofereça atendimento humano se necessário.`});return response.output_text||"Olá! Recebi sua mensagem. Vou precisar de alguns detalhes para preparar seu atendimento corretamente.";
  }
  return "Olá! 👋 Sou o assistente SellSan. Descreva o serviço que precisa e eu consulto o catálogo da empresa para preparar seu orçamento. Se preferir, posso encaminhar para um atendente.";
}

app.post('/api/whatsapp/connect-session',auth,async(req,res)=>{
  if(!PUBLIC_BASE_URL)return res.status(503).json({error:'PUBLIC_BASE_URL não configurada no servidor.'});
  if(!META_APP_ID||!META_CONFIG_ID||!META_APP_SECRET)return res.status(503).json({error:'Integração Meta ainda não configurada no servidor SellSan.'});
  const session=jwt.sign({sub:req.user.sub,purpose:'whatsapp-connect'},JWT_SECRET,{expiresIn:'10m'});res.json({url:`${PUBLIC_BASE_URL}/connect/whatsapp?session=${encodeURIComponent(session)}`});
});

app.get('/connect/whatsapp',(req,res)=>{
  const session=String(req.query.session||'');try{const p=jwt.verify(session,JWT_SECRET);if(p.purpose!=='whatsapp-connect')throw new Error();}catch{return res.status(401).send('Sessão de conexão inválida ou expirada. Volte ao SellSan e tente novamente.');}
  const appId=JSON.stringify(META_APP_ID),configId=JSON.stringify(META_CONFIG_ID),sessionJs=JSON.stringify(session);
  res.type('html').send(`<!doctype html><html lang="pt-BR"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Conectar WhatsApp • SellSan</title><style>body{margin:0;background:#0e0f12;color:#fff;font-family:Arial,sans-serif;display:grid;place-items:center;min-height:100vh}.card{width:min(92vw,460px);background:#1c1e23;border:1px solid #343741;border-radius:24px;padding:28px;box-sizing:border-box}.brand{color:#e4bd57;font-size:34px;font-weight:800}.wa{font-size:54px}.ok{color:#25d366}.muted{color:#b0b4be;line-height:1.5}button{width:100%;border:0;border-radius:14px;padding:16px;margin-top:18px;background:#e4bd57;color:#0e0f12;font-weight:800;font-size:17px}.status{margin-top:18px;padding:14px;border-radius:12px;background:#111317}</style></head><body><div class="card"><div class="brand">SellSan</div><div class="wa">💬</div><h1>Conectar WhatsApp Business</h1><p class="muted">Entre com a Meta, escolha sua empresa e o número que atenderá seus clientes. O SellSan usa a integração oficial.</p><button id="connect">Conectar com a Meta</button><div id="status" class="status muted">Aguardando conexão.</div></div><script>
  const SELL_SESSION=${sessionJs};let signup={wabaId:'',phoneNumberId:''};
  window.fbAsyncInit=function(){FB.init({appId:${appId},cookie:true,xfbml:false,version:${JSON.stringify(META_GRAPH_VERSION)}})};
  (function(d,s,id){var js,fjs=d.getElementsByTagName(s)[0];if(d.getElementById(id))return;js=d.createElement(s);js.id=id;js.src='https://connect.facebook.net/pt_BR/sdk.js';fjs.parentNode.insertBefore(js,fjs)}(document,'script','facebook-jssdk'));
  window.addEventListener('message',function(event){try{const data=typeof event.data==='string'?JSON.parse(event.data):event.data;if(data?.type==='WA_EMBEDDED_SIGNUP'){if(data.event==='FINISH'){signup={wabaId:data.data?.waba_id||'',phoneNumberId:data.data?.phone_number_id||''};document.getElementById('status').textContent='Número selecionado. Finalizando conexão…'}else if(data.event==='CANCEL'){document.getElementById('status').textContent='Conexão cancelada.'}}}catch(e){}});
  document.getElementById('connect').onclick=function(){document.getElementById('status').textContent='Abrindo Meta…';FB.login(async function(response){const code=response?.authResponse?.code;if(!code){document.getElementById('status').textContent='Não foi possível autorizar. Tente novamente.';return;}try{const r=await fetch('/api/whatsapp/embedded-signup/complete',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({session:SELL_SESSION,code,wabaId:signup.wabaId,phoneNumberId:signup.phoneNumberId})});const j=await r.json();if(!r.ok)throw new Error(j.error||'Falha ao conectar');document.getElementById('status').innerHTML='<span class="ok">✓ WhatsApp Business conectado!</span><br>'+ (j.displayPhone||'');setTimeout(()=>location.href='sellsan://whatsapp-connected',900);}catch(e){document.getElementById('status').textContent=e.message;}},{config_id:${configId},response_type:'code',override_default_response_type:true,extras:{setup:{},featureType:'whatsapp_business_app_onboarding',sessionInfoVersion:'3'}})};
</script></body></html>`);
});

app.post('/api/whatsapp/embedded-signup/complete',async(req,res)=>{
  if(!pool)return res.status(503).json({error:'PostgreSQL é necessário para conexão multiempresa.'});
  let payload;try{payload=jwt.verify(String(req.body?.session||''),JWT_SECRET);if(payload.purpose!=='whatsapp-connect')throw new Error();}catch{return res.status(401).json({error:'Sessão expirada. Volte ao SellSan e tente novamente.'});}
  const code=String(req.body?.code||''),phoneNumberId=String(req.body?.phoneNumberId||''),wabaId=String(req.body?.wabaId||'');if(!code||!phoneNumberId)return res.status(400).json({error:'A Meta não retornou o número selecionado. Tente novamente.'});
  try{
    const qs=new URLSearchParams({client_id:META_APP_ID,client_secret:META_APP_SECRET,code});const tr=await fetch(`https://graph.facebook.com/${META_GRAPH_VERSION}/oauth/access_token?${qs}`);const tj=await tr.json();if(!tr.ok||!tj.access_token)throw new Error(tj?.error?.message||'Falha ao trocar autorização por token');
    const access=tj.access_token;let displayPhone='';try{const pr=await fetch(`https://graph.facebook.com/${META_GRAPH_VERSION}/${phoneNumberId}?fields=display_phone_number,verified_name`,{headers:{Authorization:`Bearer ${access}`}});const pj=await pr.json();displayPhone=pj.display_phone_number||'';}catch{}
    if(wabaId){try{await fetch(`https://graph.facebook.com/${META_GRAPH_VERSION}/${wabaId}/subscribed_apps`,{method:'POST',headers:{Authorization:`Bearer ${access}`}})}catch{}}
    await pool.query(`INSERT INTO whatsapp_connections(user_id,waba_id,phone_number_id,display_phone,token_enc,mode,connected_at,updated_at) VALUES($1,$2,$3,$4,$5,'assist',$6,$6) ON CONFLICT(user_id) DO UPDATE SET waba_id=EXCLUDED.waba_id,phone_number_id=EXCLUDED.phone_number_id,display_phone=EXCLUDED.display_phone,token_enc=EXCLUDED.token_enc,updated_at=EXCLUDED.updated_at`,[payload.sub,wabaId,phoneNumberId,displayPhone,encryptSecret(access),Date.now()]);
    res.json({ok:true,displayPhone});
  }catch(e){console.error('Embedded signup:',e);res.status(502).json({error:e.message||'Falha ao concluir conexão.'});}
});

app.get('/api/whatsapp/webhook',(req,res)=>{const mode=req.query['hub.mode'],token=req.query['hub.verify_token'],challenge=req.query['hub.challenge'];if(mode==='subscribe'&&META_VERIFY_TOKEN&&token===META_VERIFY_TOKEN)return res.status(200).send(challenge);return res.sendStatus(403);});
app.post('/api/whatsapp/webhook',async(req,res)=>{res.sendStatus(200);try{const changes=req.body?.entry?.flatMap(e=>e.changes||[])||[];for(const change of changes){const value=change?.value||{},phoneId=String(value?.metadata?.phone_number_id||'');if(!phoneId)continue;const conn=await getConnectionByPhoneId(phoneId);if(!conn)continue;for(const m of value.messages||[]){if(m.type!=='text')continue;const waId=String(m.from||''),body=String(m.text?.body||'');if(!waId||!body)continue;await saveWaMessage(conn.userId,String(m.id||crypto.randomUUID()),waId,'in',body);if(conn.mode==='auto'){const reply=await automaticWhatsappReply(conn.userId,waId,body);const sent=await sendWhatsAppText(conn,waId,reply);await saveWaMessage(conn.userId,String(sent?.messages?.[0]?.id||crypto.randomUUID()),waId,'out',reply,'sent');}}}}catch(e){console.error('WhatsApp webhook:',e)}});

app.get('/api/whatsapp/status',auth,async(req,res)=>{const c=await getConnectionByUser(req.user.sub);res.json({configured:Boolean(c),mode:c?.mode||'assist',displayPhone:c?.displayPhone||'',phoneNumberId:c?'configured':'missing'});});
app.post('/api/whatsapp/mode',auth,async(req,res)=>{if(!pool)return res.status(503).json({error:'Banco indisponível.'});const mode=String(req.body?.mode||'');if(!['auto','assist','manual'].includes(mode))return res.status(400).json({error:'Modo inválido.'});await pool.query(`UPDATE whatsapp_connections SET mode=$1,updated_at=$2 WHERE user_id=$3`,[mode,Date.now(),req.user.sub]);res.json({ok:true,mode});});
app.get('/api/whatsapp/messages',auth,async(req,res)=>{if(!pool)return res.json({messages:[]});const rows=(await pool.query(`SELECT id,wa_id AS "waId",direction,body,created_at AS "createdAt",status FROM whatsapp_messages WHERE user_id=$1 ORDER BY created_at DESC LIMIT 200`,[req.user.sub])).rows;res.json({messages:rows});});
app.post('/api/whatsapp/send',auth,async(req,res)=>{try{const conn=await getConnectionByUser(req.user.sub);if(!conn)return res.status(409).json({error:'Conecte seu WhatsApp Business primeiro.'});const to=String(req.body?.to||'').replace(/\D/g,''),body=String(req.body?.body||'').trim();if(!to||!body)return res.status(400).json({error:'Informe telefone e mensagem.'});const sent=await sendWhatsAppText(conn,to,body);await saveWaMessage(req.user.sub,String(sent?.messages?.[0]?.id||crypto.randomUUID()),to,'out',body,'sent');res.json({ok:true,result:sent});}catch(e){res.status(502).json({error:e.message})}});
app.delete('/api/whatsapp/connection',auth,async(req,res)=>{if(pool)await pool.query(`DELETE FROM whatsapp_connections WHERE user_id=$1`,[req.user.sub]);res.json({ok:true});});

app.get('/api/services',auth,async(req,res)=>{if(!pool)return res.json({services:[]});const rows=(await pool.query(`SELECT id,name,keywords,price,unit,active FROM services WHERE user_id=$1 ORDER BY name`,[req.user.sub])).rows;res.json({services:rows});});
app.post('/api/services',auth,async(req,res)=>{if(!pool)return res.status(503).json({error:'PostgreSQL necessário para catálogo compartilhado.'});const name=String(req.body?.name||'').trim(),price=Number(req.body?.price);if(!name||!Number.isFinite(price)||price<0)return res.status(400).json({error:'Serviço ou preço inválido.'});const id=crypto.randomUUID();await pool.query(`INSERT INTO services(id,user_id,name,keywords,price,unit,active) VALUES($1,$2,$3,$4,$5,$6,TRUE)`,[id,req.user.sub,name,String(req.body?.keywords||''),price,String(req.body?.unit||'serviço')]);res.json({ok:true,id});});

app.use((_req,res)=>res.status(404).json({error:'Rota não encontrada'}));
await initDb();await initWhatsappDb();app.listen(PORT,'0.0.0.0',()=>console.log(`SellSan Server V5 on :${PORT} (${pool?'postgres':'json-local'})`));
