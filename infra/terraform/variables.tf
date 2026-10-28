variable "region" {
  type    = string
  default = "eu-central-1" # customer messages stay in the EU
}

variable "environment" {
  type = string
  validation {
    condition     = contains(["staging", "production"], var.environment)
    error_message = "environment is staging or production"
  }
}

variable "domain" {
  type        = string
  description = "Public host name of the console and webhooks, e.g. inbox.example.com"
}

variable "certificate_arn" {
  type        = string
  description = "ACM certificate for var.domain"
}

variable "images" {
  type = object({ core = string, agent = string, web = string })
}

variable "api_tasks" {
  type    = number
  default = 2
}

variable "agent_tasks" {
  type    = number
  default = 2
}

variable "db_instance_class" {
  type    = string
  default = "db.t4g.medium"
}

variable "alarm_email" {
  type = string
}

variable "inbound_mail_domain" {
  type        = string
  description = "Domain whose mail SES receives and hands to the inbox, e.g. mail.example.com"
}

variable "email_webhook_secret" {
  type        = string
  sensitive   = true
  description = "Shared secret in the SNS subscription URL for inbound mail; the state bucket is encrypted"
}
