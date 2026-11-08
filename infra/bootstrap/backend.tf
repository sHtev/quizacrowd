terraform {
  # State lives in the bucket this stack creates: -backend-config=../backend.hcl (git-ignored).
  backend "s3" {
    key          = "bootstrap/terraform.tfstate"
    region       = "eu-west-2"
    encrypt      = true
    use_lockfile = true
  }
}
