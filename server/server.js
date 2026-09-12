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

app.get("/api/health", async (_req,res)=>{let db="json-local";try{if(pool){await pool.query("SELECT 1");db="postgres";}}catch{db="postgres-error";}res.json({ok:true,service:"SellSan Server",version:"3.0.0",database:db,ai:Boolean(process.env.OPENAI_API_KEY)});});

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

app.use((_req,res)=>res.status(404).json({error:"Rota não encontrada"}));
await initDb();
app.listen(PORT,"0.0.0.0",()=>console.log(`SellSan Server on :${PORT} (${pool?"postgres":"json-local"})`));
