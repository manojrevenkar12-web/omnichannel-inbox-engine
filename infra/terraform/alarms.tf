resource "aws_sns_topic" "ops" {
  name = "${local.name}-ops"
}

resource "aws_sns_topic_subscription" "ops" {
  topic_arn = aws_sns_topic.ops.arn
  protocol  = "email"
  endpoint  = var.alarm_email
}

locals {
  alarms = {
    # Meta retries a webhook that does not get a 200 and eventually drops it: errors here are lost customer messages
    webhook-errors = {
      namespace = "AWS/ApplicationELB"
      metric    = "HTTPCode_Target_5XX_Count"
      stat      = "Sum"
      threshold = 5
      period    = 300
      compare   = "GreaterThanThreshold"
      dims      = { LoadBalancer = aws_lb.main.arn_suffix, TargetGroup = aws_lb_target_group.svc["core-api"].arn_suffix }
    }
    slow-webhooks = {
      namespace = "AWS/ApplicationELB"
      metric    = "TargetResponseTime"
      stat      = "p95"
      threshold = 1
      period    = 300
      compare   = "GreaterThanThreshold"
      dims      = { LoadBalancer = aws_lb.main.arn_suffix, TargetGroup = aws_lb_target_group.svc["core-api"].arn_suffix }
    }
    # no worker: nothing is sent and no AI turn runs, while webhooks still answer 200
    no-worker = {
      namespace = "ECS/ContainerInsights"
      metric    = "RunningTaskCount"
      stat      = "Minimum"
      threshold = 1
      period    = 60
      compare   = "LessThanThreshold"
      dims      = { ClusterName = aws_ecs_cluster.main.name, ServiceName = "core-worker" }
    }
    db-cpu = {
      namespace = "AWS/RDS"
      metric    = "CPUUtilization"
      stat      = "Average"
      threshold = 80
      period    = 300
      compare   = "GreaterThanThreshold"
      dims      = { DBInstanceIdentifier = aws_db_instance.main.identifier }
    }
    db-storage = {
      namespace = "AWS/RDS"
      metric    = "FreeStorageSpace"
      stat      = "Minimum"
      threshold = 5e9
      period    = 300
      compare   = "LessThanThreshold"
      dims      = { DBInstanceIdentifier = aws_db_instance.main.identifier }
    }
  }
}

resource "aws_cloudwatch_metric_alarm" "ops" {
  for_each            = local.alarms
  alarm_name          = "${local.name}-${each.key}"
  namespace           = each.value.namespace
  metric_name         = each.value.metric
  statistic           = contains(["Sum", "Average", "Minimum", "Maximum"], each.value.stat) ? each.value.stat : null
  extended_statistic  = contains(["Sum", "Average", "Minimum", "Maximum"], each.value.stat) ? null : each.value.stat
  threshold           = each.value.threshold
  period              = each.value.period
  evaluation_periods  = 2
  comparison_operator = each.value.compare
  dimensions          = each.value.dims
  treat_missing_data  = each.key == "no-worker" ? "breaching" : "notBreaching"
  alarm_actions       = [aws_sns_topic.ops.arn]
  ok_actions          = [aws_sns_topic.ops.arn]
}
