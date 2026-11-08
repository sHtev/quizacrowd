output "origin_certificate" {
  description = "Origin certificate (PEM), for the origin-tls Secret."
  value       = cloudflare_origin_ca_certificate.origin.certificate
}

output "origin_private_key" {
  description = "Origin certificate key (PEM), for the origin-tls Secret."
  value       = tls_private_key.origin.private_key_pem
  sensitive   = true
}

output "nameservers" {
  value = data.cloudflare_zone.this.name_servers
}
