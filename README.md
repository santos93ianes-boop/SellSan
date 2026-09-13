# SellSan V5 — WhatsApp IA + CRM + Orçamentos

A V5 redesenha o aplicativo e transforma o WhatsApp no núcleo do SellSan.

## O que mudou no app
- Interface nova em preto/grafite + dourado, com navegação inferior simples de 5 itens.
- Ícone SellSan redesenhado com **S + gráfico de crescimento**.
- Tela inicial com ações rápidas e indicadores.
- Tela **WhatsApp IA** com status real, conversas, modos de atendimento e catálogo de preços.
- `Mais` concentra Agenda, Funil, IA, Resultados e Conta para não poluir a navegação.
- Tamanhos e espaçamentos em dp para evitar os textos quebrados vistos na V4.
- URL do servidor pode vir pronta no APK via Secret do GitHub e fica oculta do comprador final.

## Fluxo do WhatsApp
1. O usuário cria/entra na conta SellSan.
2. Abre **WhatsApp → Conectar com a Meta**.
3. O backend cria uma sessão curta e abre o fluxo oficial de Embedded Signup da Meta.
4. O usuário entra com a Meta, escolhe empresa e número.
5. O servidor troca a autorização por credenciais, criptografa o token e associa aquele número à conta SellSan.
6. Mensagens chegam pelo webhook oficial da WhatsApp Business Platform.
7. O SellSan salva a conversa, identifica o serviço no catálogo e, no modo automático, responde.
8. Quando encontra um serviço, cria uma oportunidade/orçamento no CRM com o preço cadastrado.
9. A IA **não inventa preço**: preços vêm do catálogo da empresa.

## Modos de atendimento
- `IA automática`: responde automaticamente quando permitido.
- `IA sugere`: registra as mensagens; o humano assume o envio.
- `Humano`: nenhuma resposta automática.

## GitHub → APK
O workflow está em `.github/workflows/android.yml`.

Crie no repositório o Secret:

`SELLSAN_API_URL=https://api.seudominio.com`

Depois execute **Actions → Build SellSan Android APK**. O artifact será `SellSan-V5-debug-apk`.

Sem esse Secret o APK ainda compila, mas mostra a configuração avançada para informar a URL manualmente.

## Backend obrigatório para WhatsApp real
Publique `server/` com PostgreSQL e configure:

```env
PORT=8080
JWT_SECRET=uma-chave-forte
DATABASE_URL=postgresql://...
DATABASE_SSL=true
PUBLIC_BASE_URL=https://api.seudominio.com
OPENAI_API_KEY=...
OPENAI_MODEL=gpt-5.6-luna
META_VERIFY_TOKEN=um-token-de-verificacao
META_APP_ID=...
META_APP_SECRET=...
META_CONFIG_ID=...
META_GRAPH_VERSION=v23.0
WHATSAPP_TOKEN_ENCRYPTION_KEY=uma-chave-longa
```

Na Meta, o webhook do app deve apontar para:

`https://api.seudominio.com/api/whatsapp/webhook`

O `META_VERIFY_TOKEN` precisa ser o mesmo informado na configuração do webhook.

## Segurança
- Chave OpenAI nunca fica no APK.
- App Secret e tokens da Meta nunca ficam no APK.
- Tokens de WhatsApp de cada empresa são armazenados criptografados no banco.
- A sessão usada para conectar o WhatsApp expira em 10 minutos.
- Cada conta SellSan só enxerga suas próprias conversas, serviços e conexão WhatsApp.

## Importante
Nenhum projeto pode sair do ZIP já autenticado em uma conta Meta específica. A conexão real depende das credenciais do aplicativo Meta do SellSan e da autorização de cada empresa. A V5 elimina a necessidade de o comprador copiar token, Phone Number ID ou URL manualmente durante o uso normal: ele toca em **Conectar com a Meta** e segue o fluxo oficial.
