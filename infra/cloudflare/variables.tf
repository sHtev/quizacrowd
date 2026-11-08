variable "domain" {
  description = "The zone, already on Cloudflare; tenants are {tenant}.<domain>."
  type        = string
  default     = "quizacrowd.online"
}

variable "state_bucket" {
  description = "State bucket (as in ../backend.hcl), to read the load balancer address from the Hetzner stack."
  type        = string
}
