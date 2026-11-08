# The zone was created by hand when the domain moved to Cloudflare. It's looked up, not managed,
# so nothing here can delete it.
data "cloudflare_zone" "this" {
  filter = { name = var.domain }
}

data "terraform_remote_state" "hetzner" {
  backend = "s3"
  config = {
    bucket = var.state_bucket
    key    = "quizacrowd/hetzner.tfstate"
    region = "eu-west-2"
  }
}

locals {
  zone_id   = data.cloudflare_zone.this.zone_id
  origin_ip = data.terraform_remote_state.hetzner.outputs.lb_ipv4
}

# Cloudflare to origin over HTTPS, checked against the origin certificate below.
resource "cloudflare_zone_setting" "ssl" {
  zone_id    = local.zone_id
  setting_id = "ssl"
  value      = "strict"
}

resource "cloudflare_zone_setting" "always_use_https" {
  zone_id    = local.zone_id
  setting_id = "always_use_https"
  value      = "on"
}

resource "cloudflare_zone_setting" "websockets" {
  zone_id    = local.zone_id
  setting_id = "websockets"
  value      = "on"
}
