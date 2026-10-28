resource "aws_lb" "main" {
  name                       = local.name
  load_balancer_type         = "application"
  subnets                    = aws_subnet.public[*].id
  security_groups            = [aws_security_group.alb.id]
  drop_invalid_header_fields = true
}

resource "aws_lb_target_group" "svc" {
  for_each    = { core-api = "/q/health/ready", web = "/" }
  name        = "${local.name}-${each.key}"
  port        = 8080
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = aws_vpc.main.id
  health_check {
    path    = each.value
    matcher = "200"
  }
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.main.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = var.certificate_arn
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.svc["web"].arn
  }
}

# Webhooks and the console API go to the core; everything else is the console.
resource "aws_lb_listener_rule" "core" {
  listener_arn = aws_lb_listener.https.arn
  priority     = 10
  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.svc["core-api"].arn
  }
  condition {
    path_pattern {
      values = ["/webhooks/*", "/api/*"]
    }
  }
}

# Inbound email: SES receives it, SNS posts it to the core's email webhook.
resource "aws_sns_topic" "inbound_mail" {
  name = "${local.name}-inbound-mail"
}

resource "aws_sns_topic_subscription" "inbound_mail" {
  topic_arn              = aws_sns_topic.inbound_mail.arn
  protocol               = "https"
  endpoint               = "https://${var.domain}/webhooks/email?secret=${var.email_webhook_secret}"
  endpoint_auto_confirms = false
}

resource "aws_ses_receipt_rule_set" "main" {
  rule_set_name = local.name
}

resource "aws_ses_receipt_rule" "inbox" {
  name          = "to-inbox"
  rule_set_name = aws_ses_receipt_rule_set.main.rule_set_name
  recipients    = [var.inbound_mail_domain]
  enabled       = true
  scan_enabled  = true
  sns_action {
    topic_arn = aws_sns_topic.inbound_mail.arn
    encoding  = "UTF-8"
    position  = 1
  }
}
