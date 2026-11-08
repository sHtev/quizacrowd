variable "admin_cidrs" {
  description = "Addresses allowed to reach SSH and the Kubernetes API, e.g. [\"203.0.113.7/32\"]."
  type        = list(string)
}

variable "ssh_public_key" {
  description = "Public key installed for root on every server."
  type        = string
}

variable "location" {
  description = "Hetzner location for servers and the load balancer."
  type        = string
  default     = "nbg1"
}

variable "server_type" {
  description = "Hetzner server type for every node."
  type        = string
  default     = "cx23"
}

variable "agent_count" {
  description = "Number of k3s agents besides the server."
  type        = number
  default     = 1
}

variable "k3s_version" {
  description = "k3s release, matching the local k3d cluster's minor version."
  type        = string
  default     = "v1.35.10+k3s1"
}
