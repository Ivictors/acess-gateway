# Access Gateway

## Referencias autorizadas e limite de trafego

O administrador cadastra uma referencia com `POST /credential-refs` e corpo
`{"credentialRef":"card-001"}` (201; 409 se ja existir). Depois a atribui com
`POST /devices/{id}/credential-refs` e o mesmo corpo (204 idempotente; 404 se
dispositivo ou referencia nao existir). `DELETE /devices/{id}/credential-refs`
com o mesmo corpo revoga a atribuicao (204 idempotente; 404 para dispositivo
inexistente). Os tres endpoints exigem token de
administrador. Eventos novos de referencias nao atribuidas retornam 403, sem
gravar evento ou alterar presenca. Reenvios identicos de eventos antigos seguem
retornando o registro original. A migracao V4 cadastra e atribui as referencias
historicas aos respectivos dispositivos, preservando referencias compartilhadas.
Uma requisicao de ingestao autorizada antes da revogacao pode terminar; a
revogacao espera a transacao em curso e impede novas ingestoes depois dela.

O gateway usa contadores atomicos no PostgreSQL. O limite inicial por janela
fixa de 60 segundos e: 600 requisicoes por endereco de origem, 120 eventos por
dispositivo, 60 consultas e 300 demais chamadas administrativas por
administrador. Ajuste `access.rate-limit.window-seconds`,
`access.rate-limit.origin`, `access.rate-limit.events`,
`access.rate-limit.queries` e `access.rate-limit.admin`; todos devem ser
positivos. Exceder um limite retorna 429 com `Retry-After` indicando segundos
ate a proxima janela. O endereco vem da
conexao, sem confiar em `X-Forwarded-For`; clientes atras de NAT compartilham
o limite de origem. Estado expirado e removido periodicamente. Sem PostgreSQL,
a verificacao falha e a requisicao nao segue.

O controle no proxy protege antes de chegar a aplicacao, mas nao conhece a
identidade dos dispositivos por padrao. O controle na aplicacao distingue
origem, rota e principal e funciona entre instancias por usar o banco. A
metrica `gateway.security.failures` conta 401/403 por familia de rota e
resultado, sem incluir tokens, referencias ou enderecos. Monitore picos de
401/403 em cinco minutos e respostas 429. Politica inicial sugerida: alertar
operadores se uma familia de rota acumular 50 falhas 401/403 ou 10 respostas
429 em cinco minutos; manter registros por 30 dias, com acesso restrito a
operadores autorizados. Os limiares devem ser ajustados ao trafego real. Este
repositorio nao configura coletor, alerta ativo, exportacao externa ou retencao
de logs; essas medidas dependem do ambiente de observabilidade.

Backend Java para cadastrar dispositivos e receber eventos de acesso.

## Integração e entrega contínuas

O workflow `CI` executa `clean verify` com Java 21 em pull requests e em pushes para `main`. Os testes de integração usam PostgreSQL via Testcontainers; o runner precisa de Docker, mas não de um banco compartilhado nem de segredos de produção.

Uma tag `v*` executa novamente os testes e, se passarem, publica uma imagem no GitHub Container Registry com tags de versão e de commit. Exemplo, após integrar as alterações em `main`:

```powershell
git tag v0.1.0
git push origin v0.1.0
```

A imagem fica em `ghcr.io/ivictors/acess-gateway:v0.1.0`. A publicação não implanta a aplicação. O pacote no GHCR pode exigir que sua visibilidade seja alterada para pública nas configurações do pacote, caso se deseje permitir downloads anônimos. As credenciais de banco e o token administrativo pertencem ao ambiente de execução, não aos workflows.

## Autenticação

A API aceita `Authorization: Bearer <token>`. Há dois tipos de token:

- **Administrador:** configurado em `ACCESS_GATEWAY_ADMIN_TOKEN`. Pode cadastrar e consultar dispositivos, listar eventos e trocar a credencial de um dispositivo.
- **Dispositivo:** gerado no cadastro ou na troca de credencial. Pode enviar eventos somente com seu próprio `deviceId`.

O servidor não cria sessões. A rota `GET /actuator/health` é pública; as demais rotas exigem autenticação e a função adequada. As respostas que entregam uma credencial incluem `Cache-Control: no-store`.

Dispositivos inativos não conseguem autenticar com sua chave: qualquer requisição com ela recebe `401 Unauthorized`, inclusive o reenvio de um evento antigo. Se o dispositivo for reativado, a chave existente volta a funcionar. Para revogá-la definitivamente, troque a credencial.

### Configuração local

`.env.example` e `application.properties.example` contêm apenas valores fictícios para desenvolvimento. Copie o formato desejado para `.env` ou `src/main/resources/application.properties`; esses arquivos locais são ignorados pelo Git. O arquivo `application.yml` versionado mantém a leitura obrigatória de `ACCESS_GATEWAY_ADMIN_TOKEN` quando não há uma configuração local. Se copiar o exemplo de `application.properties`, substitua o token e as credenciais fictícias no próprio arquivo antes de expor a API: o token literal desse exemplo prevalece sobre a referência ao ambiente no `application.yml`.

O Spring Boot não carrega `.env` automaticamente. Para usar esse arquivo no PowerShell, carregue as variáveis no processo antes de iniciar a aplicação:

```powershell
Get-Content .env | Where-Object { $_ -and -not $_.StartsWith('#') } | ForEach-Object {
    $name, $value = $_ -split '=', 2
    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
}
.\mvnw.cmd spring-boot:run
```

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

Um evento novo com `occurredAt` mais de cinco minutos à frente do relógio do servidor recebe `400 Bad Request`, sem ser gravado nem alterar a presença. A tolerância é configurada por `access.events.max-future-skew` (padrão `5m`); o valor deve ser não negativo. O dispositivo deve corrigir seu relógio antes de enviar um novo evento com horário futuro. Um reenvio idêntico de evento já gravado continua retornando o registro original; reutilizar o mesmo `externalEventId` com dados diferentes retorna `409 Conflict`. Eventos futuros armazenados antes dessa regra não são corrigidos automaticamente e exigem inspeção dos dados de presença.

Para localizar eventos históricos com horário mais de cinco minutos à frente do recebimento, execute uma consulta de leitura no PostgreSQL:

```sql
SELECT id, device_id, external_event_id, credential_ref, occurred_at, received_at
FROM access_event
WHERE occurred_at > received_at + INTERVAL '5 minutes'
ORDER BY received_at DESC;
```

Antes de corrigir `access_presence`, analise a sequência de entrada e saída da credencial afetada; alterar somente o horário do último evento pode produzir uma presença incorreta. A tolerância reduz o desvio máximo aceito, mas um evento até cinco minutos adiantado ainda pode ser classificado antes de outro evento legítimo.

Tokens opacos permitem revogação imediata sem um servidor externo de identidade, mas exigem consulta ao banco em cada requisição de dispositivo. Um token administrativo único simplifica o início do projeto, mas não identifica operadores individualmente. Para equipes com múltiplos operadores, auditoria por pessoa ou login interativo, substitua esse token por um provedor de identidade (OIDC/OAuth2). Não coloque tokens em URL, logs ou arquivos versionados.
