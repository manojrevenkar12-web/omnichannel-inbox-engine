output "load_balancer_dns" {
  value = aws_lb.main.dns_name
}

output "webhook_urls" {
  value = {
    whatsapp  = "https://${var.domain}/webhooks/whatsapp"
    instagram = "https://${var.domain}/webhooks/instagram"
    sms       = "https://${var.domain}/webhooks/sms"
  }
}
