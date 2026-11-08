terraform {
  required_version = ">= 1.10"

  required_providers {
    hcloud = { source = "hetznercloud/hcloud", version = "~> 1.70" }
    random = { source = "hashicorp/random", version = "~> 3.6" }
  }

  # State bucket passed at init: -backend-config=../backend.hcl (git-ignored), AWS_PROFILE=shtev-admin.
  # use_lockfile = S3-native state locking (no DynamoDB table).
  backend "s3" {
    key          = "quizacrowd/hetzner.tfstate"
    region       = "eu-west-2"
    encrypt      = true
    use_lockfile = true
  }
}
