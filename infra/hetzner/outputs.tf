output "lb_ipv4" {
  description = "Public address of the load balancer (DNS points here)."
  value       = hcloud_load_balancer.web.ipv4
}

output "server_ipv4" {
  description = "Public address of the k3s server (SSH and the Kubernetes API)."
  value       = hcloud_primary_ip.server.ip_address
}

output "agent_ipv4" {
  description = "Public addresses of the agents (SSH)."
  value       = hcloud_server.agent[*].ipv4_address
}
