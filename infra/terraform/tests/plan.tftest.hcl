# Plan-only tests with a mocked AWS provider: no credentials, no cost. They pin the decisions
# that must not drift: private, encrypted data; one worker; TLS only; alarms that page.
mock_provider "aws" {}

override_data {
  target = data.aws_availability_zones.available
  values = { names = ["eu-central-1a", "eu-central-1b", "eu-central-1c"] }
}

variables {
  environment          = "production"
  domain               = "inbox.example.com"
  certificate_arn      = "arn:aws:acm:eu-central-1:123456789012:certificate/test"
  images               = { core = "core:1", agent = "agent:1", web = "web:1" }
  alarm_email          = "ops@example.com"
  inbound_mail_domain  = "mail.example.com"
  email_webhook_secret = "test-secret"
}

run "production_plan" {
  command = plan

  assert {
    condition     = aws_db_instance.main.storage_encrypted && !aws_db_instance.main.publicly_accessible
    error_message = "the database holds customer messages: encrypted and private"
  }
  assert {
    condition     = aws_db_instance.main.multi_az && aws_db_instance.main.deletion_protection
    error_message = "production runs Multi-AZ with deletion protection"
  }
  assert {
    condition     = aws_ecs_service.svc["core-worker"].desired_count == 1 && aws_ecs_service.svc["core-worker"].deployment_maximum_percent == 100
    error_message = "exactly one worker, also during deploys: the sender's rate limits live in its memory"
  }
  assert {
    condition     = aws_lb_listener.https.protocol == "HTTPS" && aws_lb_listener.https.port == 443
    error_message = "the only listener is HTTPS on 443"
  }
  assert {
    condition     = length(aws_cloudwatch_metric_alarm.ops) >= 5 && alltrue([for a in aws_cloudwatch_metric_alarm.ops : length(a.alarm_actions) > 0])
    error_message = "every alarm notifies someone"
  }
}

run "staging_is_cheaper_but_still_private" {
  command = plan
  variables {
    environment = "staging"
  }
  assert {
    condition     = !aws_db_instance.main.multi_az && !aws_db_instance.main.publicly_accessible
    error_message = "staging: single AZ, still private"
  }
}

run "unknown_environment_is_rejected" {
  command = plan
  variables {
    environment = "dev"
  }
  expect_failures = [var.environment]
}
