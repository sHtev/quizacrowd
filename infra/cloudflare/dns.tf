# Apex and every tenant subdomain go through Cloudflare's proxy to the Hetzner load balancer.
# The free plan's certificate covers one wildcard level: {tenant}.<domain>.
resource "cloudflare_dns_record" "apex" {
  zone_id = local.zone_id
  name    = var.domain
  type    = "A"
  content = local.origin_ip
  proxied = true
  ttl     = 1 # automatic; required for proxied records
}

resource "cloudflare_dns_record" "tenants" {
  zone_id = local.zone_id
  name    = "*.${var.domain}"
  type    = "A"
  content = local.origin_ip
  proxied = true
  ttl     = 1
}
