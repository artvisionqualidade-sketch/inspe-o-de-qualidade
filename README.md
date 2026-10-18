# QualiInsp 3.0 — Inspeção e rastreabilidade de qualidade

Aplicativo Android para registrar inspeções visuais, cadastrar produtos/padrões, guardar exemplos de treinamento, registrar ocorrências e exportar histórico. O projeto foi reorganizado para priorizar evidências, rastreabilidade e resultados inconclusivos quando faltam dados.

## Recursos
- Painel inicial com resumo de inspeções, produtos e exemplos cadastrados.
- Cadastro de produtos com imagem de padrão aprovado.
- Cadastro de defeitos, regras textuais, criticidade e exemplos por câmera/galeria.
- Inspeção por foto, câmera ao vivo e gravação de vídeo para amostragem de ângulos.
- Histórico com lote, máquina, molde e cavidade.
- Checklist de produção com foto obrigatória para ocorrências não conformes.
- Compartilhamento do histórico/relatório.
- Fluxo conservador: sem padrão ou exemplos de defeitos, o resultado fica inconclusivo em vez de aprovar automaticamente.

## Compilar pelo GitHub Actions
O repositório não inclui `gradlew`; o workflow usa Gradle instalado no runner. Abra a pasta `.github/workflows` e use o arquivo `android.yml`. Execute o workflow na aba **Actions** e baixe `QualiInsp-APK-debug` em **Artifacts** após sucesso.

## Importante sobre precisão
A comparação visual atual é heurística e experimental (histogramas de cor, luminosidade e bordas), não uma rede neural industrial treinada. Não mede dimensões físicas reais e não substitui instrumentos, gabaritos ou validação de processo. A câmera do celular pode apoiar inspeção visual, mas resultados devem ser validados com amostras representativas e condições de iluminação controladas antes de qualquer decisão industrial.
