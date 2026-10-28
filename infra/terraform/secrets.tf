# Values are set outside Terraform (console or CLI) so they never sit in state as plain text.
resource "aws_secretsmanager_secret" "app" {
  for_each = toset(["WHATSAPP_APP_SECRET", "WHATSAPP_VERIFY_TOKEN", "WHATSAPP_ACCESS_TOKEN", "INSTAGRAM_APP_SECRET", "SMS_AUTH_TOKEN", "EMAIL_WEBHOOK_SECRET", "ANTHROPIC_API_KEY"])
  name     = "${local.name}/${each.key}"
}
