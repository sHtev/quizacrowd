resource "hcloud_network" "cluster" {
  name     = "quizacrowd"
  ip_range = "10.0.0.0/16"
  labels   = local.labels
}

resource "hcloud_network_subnet" "nodes" {
  network_id   = hcloud_network.cluster.id
  type         = "cloud"
  network_zone = "eu-central"
  ip_range     = "10.0.1.0/24"
}

# Applies to the public interface only; traffic on the private network (load balancer, node to node)
# isn't filtered. Web traffic arrives through the load balancer, so 80/443 stay closed here.
resource "hcloud_firewall" "nodes" {
  name   = "quizacrowd-nodes"
  labels = local.labels

  rule {
    description = "SSH"
    direction   = "in"
    protocol    = "tcp"
    port        = "22"
    source_ips  = var.admin_cidrs
  }

  rule {
    description = "Kubernetes API"
    direction   = "in"
    protocol    = "tcp"
    port        = "6443"
    source_ips  = var.admin_cidrs
  }

  rule {
    description = "Ping"
    direction   = "in"
    protocol    = "icmp"
    source_ips  = ["0.0.0.0/0", "::/0"]
  }
}
