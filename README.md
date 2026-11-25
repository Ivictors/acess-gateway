# Access Gateway

Backend Java para cadastrar dispositivos e receber eventos de acesso.

## Autenticação

A API aceita `Authorization: Bearer <token>`. Há dois tipos de token:

- **Administrador:** configurado em `ACCESS_GATEWAY_ADMIN_TOKEN`. Pode cadastrar e consultar dispositivos, listar eventos e trocar a credencial de um dispositivo.
- **Dispositivo:** gerado no cadastro ou na troca de credencial. Pode enviar eventos somente com seu próprio `deviceId`.

O servidor não cria sessões. A rota `GET /actuator/health` é pública; as demais rotas exigem autenticação e a função adequada. As respostas que entregam uma credencial incluem `Cache-Control: no-store`.

### Configuração local

Defina as variáveis do banco (`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`) e gere um segredo administrativo aleatório. No PowerShell:

```powershell
$adminBytes = New-Object byte[] 32
$adminRandom = [System.Security.Cryptography.RandomNumberGenerator]::Create()
$adminRandom.GetBytes($adminBytes)
$adminRandom.Dispose()
$env:ACCESS_GATEWAY_ADMIN_TOKEN = [Convert]::ToBase64String($adminBytes)
.\mvnw.cmd spring-boot:run
```

Sem `ACCESS_GATEWAY_ADMIN_TOKEN`, ou com menos de 32 caracteres, a aplicação falha ao iniciar. Guarde o token fora do repositório e use **HTTPS** entre clientes e servidor; Bearer tokens podem ser usados por quem os obtiver. Se houver proxy reverso, configure TLS nele e bloqueie acesso direto à porta HTTP da aplicação.

### Cadastrar um dispositivo

`POST /devices` exige o token administrativo. A resposta contém os dados do dispositivo e `apiKey`. A chave é mostrada **uma vez**; salve-a no dispositivo antes de descartar a resposta. O banco armazena apenas o hash SHA-256 da chave aleatória.

```http
POST /devices
Authorization: Bearer <token-administrativo>
Content-Type: application/json

{"serialNumber":"SN-001","name":"Entrada"}
```

```json
{
  "device": {"id": "<uuid>", "serialNumber": "SN-001", "name": "Entrada", "active": true, "createdAt": "<instante>"},
  "apiKey": "agd_<valor-aleatorio>"
}
```

Use `Authorization: Bearer <apiKey>` ao chamar `POST /device-events`. O `deviceId` do corpo deve ser o ID associado à chave; outro ID resulta em `403 Forbidden`.

### Trocar a credencial

`POST /devices/{id}/credentials` exige o token administrativo e retorna uma nova `apiKey`. Após a troca, novas requisições com a chave antiga são rejeitadas. Uma requisição já autenticada antes da troca ainda pode terminar. Se o dispositivo perder a chave antes de recebê-la, repita a troca com o token administrativo; o servidor não consegue recuperar a chave anterior a partir do hash.

Dispositivos cadastrados antes da migração V2 ainda não têm chave. Use a mesma rota para gerar a primeira credencial deles.

## Escolhas e limites

### Presença na área (fase 1)

Todos os dispositivos pertencem à mesma área nesta versão. `credentialRef` identifica a mesma credencial em todos eles. O sistema parte do estado **fora** quando ainda não há entrada ou saída conhecida. Uma entrada (`ACCESS_GRANTED`) marca a credencial como dentro; uma saída (`EXIT_REGISTERED`) marca como fora. `ACCESS_DENIED` não muda a posição.

Cada evento recebe uma `classification` na resposta de ingestão e na consulta: `NORMAL`, `ENTRY_WHILE_INSIDE`, `EXIT_WHILE_OUTSIDE` ou `OUT_OF_ORDER`. Uma segunda entrada ou saída é registrada com sua classificação, sem alterar o `eventType` informado pelo dispositivo. Reenvios do mesmo `externalEventId` mantêm a classificação original. Eventos anteriores à migração V3 aparecem como `UNCLASSIFIED`; a posição inicial na migração usa o evento de entrada ou saída mais recente de cada credencial.

Para entradas e saídas novas, um `occurredAt` igual ou anterior ao último evento de transição aplicado é `OUT_OF_ORDER`: fica no histórico, mas não altera a posição atual. Eventos de acesso negado não participam dessa ordem porque não alteram a posição. O gateway recebe eventos após a decisão do dispositivo; esta classificação não impede uma porta de abrir. A prevenção física exigirá uma solicitação de autorização antes da abertura e uma política para funcionamento sem conexão.

Tokens opacos permitem revogação imediata sem um servidor externo de identidade, mas exigem consulta ao banco em cada requisição de dispositivo. Um token administrativo único simplifica o início do projeto, mas não identifica operadores individualmente. Para equipes com múltiplos operadores, auditoria por pessoa ou login interativo, substitua esse token por um provedor de identidade (OIDC/OAuth2). Não coloque tokens em URL, logs ou arquivos versionados.
