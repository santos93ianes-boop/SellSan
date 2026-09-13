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



// ===== SellSan V4 • WhatsApp Business Platform =====
// Configure META_VERIFY_TOKEN, META_ACCESS_TOKEN and META_PHONE_NUMBER_ID on the server.
// Prices always come from the SellSan service catalog; the AI is never allowed to invent a price.
const META_VERIFY_TOKEN = process.env.META_VERIFY_TOKEN || "";
const META_ACCESS_TOKEN = process.env.META_ACCESS_TOKEN || "";
const META_PHONE_NUMBER_ID = process.env.META_PHONE_NUMBER_ID || "";
const META_GRAPH_VERSION = process.env.META_GRAPH_VERSION || "v23.0";
const AUTO_REPLY = String(process.env.WHATSAPP_AUTO_REPLY || "true") === "true";

async function initWhatsappDb(){
  if(!pool) return;
  await pool.query(`CREATE TABLE IF NOT EXISTS services (id TEXT PRIMARY KEY, user_id TEXT REFERENCES users(id) ON DELETE CASCADE, name TEXT NOT NULL, keywords TEXT NOT NULL DEFAULT '', price NUMERIC(12,2) NOT NULL, unit TEXT NOT NULL DEFAULT 'serviço', active BOOLEAN NOT NULL DEFAULT TRUE)`);
  await pool.query(`CREATE TABLE IF NOT EXISTS whatsapp_messages (id TEXT PRIMARY KEY, wa_id TEXT NOT NULL, direction TEXT NOT NULL, body TEXT NOT NULL, created_at BIGINT NOT NULL, status TEXT NOT NULL DEFAULT 'received')`);
}

async function sendWhatsAppText(to, body){
  if(!META_ACCESS_TOKEN || !META_PHONE_NUMBER_ID) throw new Error("WhatsApp não configurado no servidor");
  const r = await fetch(`https://graph.facebook.com/${META_GRAPH_VERSION}/${META_PHONE_NUMBER_ID}/messages`, {
    method:"POST", headers:{"Authorization":`Bearer ${META_ACCESS_TOKEN}`,"Content-Type":"application/json"},
    body:JSON.stringify({messaging_product:"whatsapp",to,type:"text",text:{preview_url:false,body:String(body).slice(0,4000)}})
  });
  const j=await r.json(); if(!r.ok) throw new Error(j?.error?.message || "Falha ao enviar WhatsApp"); return j;
}

async function saveWaMessage(id, waId, direction, body, status="received"){
  if(pool) await pool.query(`INSERT INTO whatsapp_messages(id,wa_id,direction,body,created_at,status) VALUES($1,$2,$3,$4,$5,$6) ON CONFLICT(id) DO NOTHING`,[id,waId,direction,body,Date.now(),status]);
}

async function catalogMatch(text){
  if(!pool) return null;
  const rows=(await pool.query(`SELECT id,name,keywords,price,unit FROM services WHERE active=TRUE ORDER BY name`)).rows;
  const t=String(text).toLowerCase();
  let best=null,score=0;
  for(const x of rows){const words=[x.name,...String(x.keywords||'').split(',')].map(v=>v.trim().toLowerCase()).filter(Boolean);const sc=words.filter(w=>t.includes(w)).length;if(sc>score){score=sc;best=x}}
  return best;
}

async function automaticWhatsappReply(waId, text){
  const service=await catalogMatch(text);
  if(service){
    return `Olá! Sou o assistente SellSan. Encontrei o serviço “${service.name}” no catálogo por R$ ${Number(service.price).toFixed(2).replace('.',',')} (${service.unit}). Para preparar o orçamento correto, me informe seu nome e os detalhes/quantidade do serviço. O valor final só será confirmado conforme as regras cadastradas pela empresa.`;
  }
  if(process.env.OPENAI_API_KEY){
    const client=new OpenAI({apiKey:process.env.OPENAI_API_KEY});
    const response=await client.responses.create({model:MODEL,store:false,input:`Você é o atendente SellSan no WhatsApp. O cliente escreveu: ${JSON.stringify(String(text).slice(0,1500))}. Responda em português do Brasil, cordialmente, em até 500 caracteres. Não invente preço, desconto, prazo, disponibilidade ou serviço. Se o cliente pedir preço/orçamento e não houver preço fornecido, diga que precisa identificar o serviço e peça somente as informações essenciais. Ofereça atendimento humano quando necessário.`});
    return response.output_text || "Olá! Recebi sua mensagem. Vou precisar de alguns detalhes para preparar seu atendimento corretamente.";
  }
  return "Olá! Sou o assistente SellSan. Recebi sua mensagem. Para preparar seu orçamento, informe seu nome e descreva o serviço que precisa. Se preferir, posso encaminhar para um atendente.";
}

app.get("/api/whatsapp/webhook",(req,res)=>{
  const mode=req.query["hub.mode"], token=req.query["hub.verify_token"], challenge=req.query["hub.challenge"];
  if(mode==="subscribe" && META_VERIFY_TOKEN && token===META_VERIFY_TOKEN) return res.status(200).send(challenge);
  return res.sendStatus(403);
});

app.post("/api/whatsapp/webhook",async(req,res)=>{
  // Acknowledge Meta quickly; process after parsing this small payload.
  res.sendStatus(200);
  try{
    const changes=req.body?.entry?.flatMap(e=>e.changes||[])||[];
    for(const change of changes){
      const value=change?.value||{};
      for(const m of value.messages||[]){
        if(m.type!=="text") continue;
        const waId=String(m.from||""); const body=String(m.text?.body||""); if(!waId||!body) continue;
        await saveWaMessage(String(m.id||crypto.randomUUID()),waId,"in",body);
        if(AUTO_REPLY){const reply=await automaticWhatsappReply(waId,body);const sent=await sendWhatsAppText(waId,reply);await saveWaMessage(String(sent?.messages?.[0]?.id||crypto.randomUUID()),waId,"out",reply,"sent");}
      }
    }
  }catch(e){console.error("WhatsApp webhook:",e)}
});

app.get("/api/whatsapp/status",auth,async(_req,res)=>res.json({configured:Boolean(META_VERIFY_TOKEN&&META_ACCESS_TOKEN&&META_PHONE_NUMBER_ID),autoReply:AUTO_REPLY,phoneNumberId:META_PHONE_NUMBER_ID?"configured":"missing"}));
app.get("/api/whatsapp/messages",auth,async(req,res)=>{if(!pool)return res.json({messages:[]});const rows=(await pool.query(`SELECT id,wa_id AS "waId",direction,body,created_at AS "createdAt",status FROM whatsapp_messages ORDER BY created_at DESC LIMIT 200`)).rows;res.json({messages:rows});});
app.post("/api/whatsapp/send",auth,async(req,res)=>{try{const to=String(req.body?.to||'').replace(/\D/g,'');const body=String(req.body?.body||'').trim();if(!to||!body)return res.status(400).json({error:'Informe telefone e mensagem.'});const sent=await sendWhatsAppText(to,body);await saveWaMessage(String(sent?.messages?.[0]?.id||crypto.randomUUID()),to,'out',body,'sent');res.json({ok:true,result:sent});}catch(e){res.status(502).json({error:e.message})}});

app.get("/api/services",auth,async(req,res)=>{if(!pool)return res.json({services:[]});const rows=(await pool.query(`SELECT id,name,keywords,price,unit,active FROM services WHERE user_id=$1 ORDER BY name`,[req.user.sub])).rows;res.json({services:rows});});
app.post("/api/services",auth,async(req,res)=>{if(!pool)return res.status(503).json({error:'PostgreSQL necessário para catálogo compartilhado.'});const name=String(req.body?.name||'').trim(),price=Number(req.body?.price);if(!name||!Number.isFinite(price)||price<0)return res.status(400).json({error:'Serviço ou preço inválido.'});const id=crypto.randomUUID();await pool.query(`INSERT INTO services(id,user_id,name,keywords,price,unit,active) VALUES($1,$2,$3,$4,$5,$6,TRUE)`,[id,req.user.sub,name,String(req.body?.keywords||''),price,String(req.body?.unit||'serviço')]);res.json({ok:true,id});});

app.use((_req,res)=>res.status(404).json({error:"Rota não encontrada"}));
await initDb();
await initWhatsappDb();
app.listen(PORT,"0.0.0.0",()=>console.log(`SellSan Server on :${PORT} (${pool?"postgres":"json-local"})`));
