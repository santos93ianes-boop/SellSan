# SellSan V3 — App + Backend + IA

SellSan é um aplicativo Android para prestadores de serviço e pequenos negócios que vendem por atendimento direto/WhatsApp. Esta versão inclui o app Android e o servidor necessário para login, sincronização e IA.

## O que funciona no APK
- Painel com vendas fechadas, potencial, clientes e follow-ups
- Cadastro de clientes
- Orçamentos e status comerciais
- Funil: Novo → Conversando → Orçamento → Follow-up → Fechado → Perdido
- Agenda de atendimentos
- Indicadores de faturamento, perdas e conversão
- Compartilhamento de mensagem pelo WhatsApp
- Geração e compartilhamento de PDF do orçamento
- Lembrete local de follow-up para o dia seguinte
- Entrada por voz na área SellSan IA
- Modo local offline
- Cadastro/login no servidor
- Sincronização entre celular e nuvem
- SellSan IA via backend protegido

## Estrutura
- `app/` — aplicativo Android nativo Kotlin
- `server/` — API Node.js/Express
- `docker-compose.yml` — API + PostgreSQL para ambiente próprio
- `render.yaml` — base para hospedagem do backend em serviço compatível com Docker
- `.github/workflows/android.yml` — geração automática do APK no GitHub

## 1. Gerar o APK no GitHub
1. Crie um repositório vazio.
2. Envie **todo o conteúdo deste ZIP** para a raiz do repositório.
3. Abra **Actions**.
4. Execute **Build SellSan Android APK**.
5. Baixe o artifact `SellSan-V3-debug-apk`.

O APK funciona imediatamente em **modo local**. Clientes, orçamentos, funil, agenda, PDF, lembretes e WhatsApp não dependem do servidor.

## 2. Colocar nuvem + login + IA para funcionar
O servidor precisa estar publicado em uma URL HTTPS. Ele usa estas variáveis:

```env
PORT=8080
JWT_SECRET=uma-chave-grande-e-secreta
DATABASE_URL=postgresql://usuario:senha@host:5432/sellsan
DATABASE_SSL=true
OPENAI_API_KEY=sk-...
OPENAI_MODEL=gpt-5.6-luna
CORS_ORIGIN=*
```

**Nunca coloque `OPENAI_API_KEY` dentro do APK ou no código público do GitHub.**

Depois de publicar o backend:
1. Abra o SellSan no celular.
2. Entre em **Conta**.
3. Informe a URL HTTPS do servidor, por exemplo `https://api.seudominio.com`.
4. Salve.
5. Crie uma conta SellSan.
6. Toque em **Sincronizar nuvem**.
7. Abra **IA** e faça uma pergunta.

## 3. Testar o backend localmente com Docker
Com Docker instalado:

```bash
export OPENAI_API_KEY="sua-chave"
docker compose up --build
```

API: `http://localhost:8080`
Health check: `http://localhost:8080/api/health`

Para Android físico, `localhost` não aponta para o computador. Use o IP local do computador, por exemplo `http://192.168.0.10:8080`, apenas para teste na mesma rede. Em produção use HTTPS.

## Banco de dados
- Com `DATABASE_URL`: PostgreSQL persistente.
- Sem `DATABASE_URL`: fallback JSON local do servidor, indicado apenas para desenvolvimento.

A sincronização une registros pelo campo `id` para reduzir risco de sobrescrever registros criados em outro aparelho.

## IA
A IA é chamada exclusivamente pelo backend em `POST /api/ai`. O servidor usa a OpenAI Responses API. O modelo padrão pode ser alterado pela variável `OPENAI_MODEL`.

## Segurança implementada
- Senhas com bcrypt
- Login com JWT
- OpenAI key somente no servidor
- Helmet
- Rate limit
- Limite de payload
- Banco PostgreSQL em produção
- Segredos fora do APK
- Resposta de IA limitada ao contexto enviado

## Antes de vender em produção
Esta versão já contém toda a arquitetura funcional necessária para operar app + servidor + IA. Para comercialização em escala, ainda é recomendável adicionar: recuperação de senha por e-mail, termos/LGPD, exclusão de conta, cobrança/assinatura, logs/monitoramento, backup automatizado e assinatura release da Play Store.
