# Cloudflare Origin CA certificate for Traefik: trusted only by Cloudflare, which is the only client
# that should reach the origin. The key is generated here and kept in Terraform state (encrypted bucket).
resource "tls_private_key" "origin" {
  algorithm   = "ECDSA"
  ecdsa_curve = "P256"
}

resource "tls_cert_request" "origin" {
  private_key_pem = tls_private_key.origin.private_key_pem
  dns_names       = [var.domain, "*.${var.domain}"]

  subject {
    common_name = var.domain
  }
}

resource "cloudflare_origin_ca_certificate" "origin" {
  csr                = tls_cert_request.origin.cert_request_pem
  hostnames          = [var.domain, "*.${var.domain}"]
  request_type       = "origin-ecc"
  requested_validity = 5475 # 15 years
}
