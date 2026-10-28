resource "aws_db_subnet_group" "main" {
  name       = local.name
  subnet_ids = aws_subnet.private[*].id
}

# PostgreSQL is the record of every message, the webhook inbox and the outbox (docs/adr/0001).
resource "aws_db_instance" "main" {
  identifier                      = local.name
  engine                          = "postgres"
  engine_version                  = "16"
  instance_class                  = var.db_instance_class
  allocated_storage               = 50
  max_allocated_storage           = 500
  storage_encrypted               = true
  db_name                         = "inbox"
  username                        = "inbox"
  manage_master_user_password     = true
  multi_az                        = var.environment == "production"
  db_subnet_group_name            = aws_db_subnet_group.main.name
  vpc_security_group_ids          = [aws_security_group.db.id]
  publicly_accessible             = false
  backup_retention_period         = 14
  deletion_protection             = var.environment == "production"
  skip_final_snapshot             = var.environment != "production"
  final_snapshot_identifier       = "${local.name}-final"
  performance_insights_enabled    = true
  auto_minor_version_upgrade      = true
  copy_tags_to_snapshot           = true
  enabled_cloudwatch_logs_exports = ["postgresql"]
}
