resource "aws_ecs_cluster" "main" {
  name = local.name
  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}

resource "aws_service_discovery_http_namespace" "main" {
  name = local.name
}

resource "aws_cloudwatch_log_group" "svc" {
  for_each          = toset(["core-api", "core-worker", "agent", "web"])
  name              = "/${local.name}/${each.key}"
  retention_in_days = 30
}

resource "aws_iam_role" "execution" {
  name = "${local.name}-execution"
  assume_role_policy = jsonencode({
    Version   = "2012-10-17"
    Statement = [{ Effect = "Allow", Principal = { Service = "ecs-tasks.amazonaws.com" }, Action = "sts:AssumeRole" }]
  })
}

resource "aws_iam_role_policy_attachment" "execution" {
  role       = aws_iam_role.execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

# Tasks may read exactly their secrets and nothing else.
resource "aws_iam_role_policy" "secrets" {
  role = aws_iam_role.execution.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["secretsmanager:GetSecretValue"]
      Resource = concat([for s in aws_secretsmanager_secret.app : s.arn], [aws_db_instance.main.master_user_secret[0].secret_arn])
    }]
  })
}

locals {
  db_secret = aws_db_instance.main.master_user_secret[0].secret_arn
  core_env = [
    { name = "DATABASE_URL", value = "jdbc:postgresql://${aws_db_instance.main.address}:5432/inbox" },
    { name = "DATABASE_USER", value = "inbox" },
    { name = "AGENT_URL", value = "http://agent:8090" },
    { name = "CONSOLE_ORIGIN", value = "https://${var.domain}" },
    { name = "SMS_PUBLIC_URL", value = "https://${var.domain}/webhooks/sms" },
  ]
  core_secrets = concat(
    [{ name = "DATABASE_PASSWORD", valueFrom = "${local.db_secret}:password::" }],
    [for k in ["WHATSAPP_APP_SECRET", "WHATSAPP_VERIFY_TOKEN", "WHATSAPP_ACCESS_TOKEN", "INSTAGRAM_APP_SECRET", "SMS_AUTH_TOKEN", "EMAIL_WEBHOOK_SECRET"] :
    { name = k, valueFrom = aws_secretsmanager_secret.app[k].arn }],
  )
  # The API tasks answer webhooks and the console; one worker task drains the inbox and outbox.
  # The sender partitions business numbers by instance (docs/adr/0001), so workers scale by
  # running more worker services with INBOX_SENDER_INSTANCE set, not by raising desired_count.
  containers = {
    core-api = {
      image = var.images.core, port = 8080, cpu = 1024, memory = 2048
      env   = concat(local.core_env, [{ name = "INBOX_WORKERS_ENABLED", value = "false" }]), secrets = local.core_secrets
    }
    core-worker = {
      image = var.images.core, port = 8080, cpu = 1024, memory = 2048
      env = concat(local.core_env, [
        { name = "INBOX_WORKERS_ENABLED", value = "true" },
        { name = "INBOX_SENDER_INSTANCE", value = "0" },
        { name = "INBOX_SENDER_INSTANCES", value = "1" },
      ]), secrets = local.core_secrets
    }
    agent = {
      image = var.images.agent, port = 8090, cpu = 512, memory = 1024
      env = [], secrets = [{ name = "ANTHROPIC_API_KEY", valueFrom = aws_secretsmanager_secret.app["ANTHROPIC_API_KEY"].arn }]
    }
    web = { image = var.images.web, port = 8080, cpu = 256, memory = 512, env = [], secrets = [] }
  }
  desired = { core-api = var.api_tasks, core-worker = 1, agent = var.agent_tasks, web = 2 }
  # names other services reach through Service Connect
  discovery = { core-api = "core", agent = "agent" }
}

resource "aws_ecs_task_definition" "svc" {
  for_each                 = local.containers
  family                   = "${local.name}-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = each.value.cpu
  memory                   = each.value.memory
  execution_role_arn       = aws_iam_role.execution.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "ARM64"
  }
  container_definitions = jsonencode([{
    name         = each.key
    image        = each.value.image
    essential    = true
    portMappings = [{ name = each.key, containerPort = each.value.port, protocol = "tcp" }]
    environment  = each.value.env
    secrets      = each.value.secrets
    logConfiguration = {
      logDriver = "awslogs"
      options = {
        awslogs-group         = aws_cloudwatch_log_group.svc[each.key].name
        awslogs-region        = var.region
        awslogs-stream-prefix = each.key
      }
    }
  }])
}

resource "aws_ecs_service" "svc" {
  for_each        = local.containers
  name            = each.key
  cluster         = aws_ecs_cluster.main.id
  task_definition = aws_ecs_task_definition.svc[each.key].arn
  desired_count   = local.desired[each.key]
  launch_type     = "FARGATE"

  # one worker drains the outbox; never two copies of it during a deploy
  deployment_minimum_healthy_percent = each.key == "core-worker" ? 0 : 50
  deployment_maximum_percent         = each.key == "core-worker" ? 100 : 200
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets         = aws_subnet.private[*].id
    security_groups = [aws_security_group.services.id]
  }

  service_connect_configuration {
    enabled   = true
    namespace = aws_service_discovery_http_namespace.main.arn
    dynamic "service" {
      for_each = contains(keys(local.discovery), each.key) ? [each.key] : []
      content {
        port_name = each.key
        client_alias {
          port     = each.value.port
          dns_name = local.discovery[each.key]
        }
      }
    }
  }

  dynamic "load_balancer" {
    for_each = contains(["core-api", "web"], each.key) ? [each.key] : []
    content {
      target_group_arn = aws_lb_target_group.svc[each.key].arn
      container_name   = each.key
      container_port   = each.value.port
    }
  }
}
