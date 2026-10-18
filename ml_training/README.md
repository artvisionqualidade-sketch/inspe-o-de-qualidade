# Dataset recomendado para a próxima versão

Para cada produto, organizar:
- `conforme/`
- `nao_conforme/rebarba/`
- `nao_conforme/ponto_injecao/`
- `nao_conforme/cor/`
- `nao_conforme/riscos/`
- `nao_conforme/trinca/`
- `nao_conforme/rosca/`
- `nao_conforme/encaixe/`
- outros defeitos definidos pelo padrão do cliente.

Capturar múltiplas condições de iluminação, rotação e posição, mantendo também um conjunto de validação separado.

A meta da próxima etapa é substituir o classificador simples por modelo de visão computacional/TFLite treinado e validado com métricas por defeito.
