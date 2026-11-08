terraform {
  required_version = ">= 1.10"

  required_providers {
    cloudflare = { source = "cloudflare/cloudflare", version = "~> 5.28" }
    tls        = { source = "hashicorp/tls", version = "~> 4.0" }
  }

  # Same state bucket as infra/hetzner: -backend-config=../backend.hcl (git-ignored), AWS_PROFILE=shtev-admin.
  backend "s3" {
    key          = "quizacrowd/cloudflare.tfstate"
    region       = "eu-west-2"
    encrypt      = true
    use_lockfile = true
  }
}
