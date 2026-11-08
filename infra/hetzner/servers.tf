locals {
  labels     = { project = "quizacrowd" }
  server_ip  = "10.0.1.1"
  agent_ips  = [for i in range(var.agent_count) : cidrhost(hcloud_network_subnet.nodes.ip_range, 11 + i)]
  k3s_server = "https://${local.server_ip}:6443"
}

resource "hcloud_ssh_key" "admin" {
  name       = "quizacrowd-admin"
  public_key = var.ssh_public_key
  labels     = local.labels
}

# Shared secret agents use to join the cluster.
resource "random_password" "k3s_token" {
  length  = 48
  special = false
}

# Reserved first, so the server's public address is known for k3s's certificate (kubectl from outside).
resource "hcloud_primary_ip" "server" {
  name        = "quizacrowd-server-1"
  type        = "ipv4"
  location    = var.location
  auto_delete = false
  labels      = local.labels
}

resource "hcloud_server" "server" {
  name         = "quizacrowd-server-1"
  server_type  = var.server_type
  image        = "ubuntu-24.04"
  location     = var.location
  ssh_keys     = [hcloud_ssh_key.admin.id]
  firewall_ids = [hcloud_firewall.nodes.id]
  labels       = merge(local.labels, { role = "server" })

  public_net {
    ipv4 = hcloud_primary_ip.server.id
  }

  network {
    network_id = hcloud_network.cluster.id
    ip         = local.server_ip
  }

  user_data = templatefile("${path.module}/cloud-init-server.yaml.tftpl", {
    k3s_version = var.k3s_version
    k3s_token   = random_password.k3s_token.result
    node_ip     = local.server_ip
    public_ip   = hcloud_primary_ip.server.ip_address
  })

  depends_on = [hcloud_network_subnet.nodes]

  lifecycle {
    # cloud-init only runs on first boot; a changed template shouldn't rebuild the node.
    ignore_changes = [user_data, ssh_keys]
  }
}

resource "hcloud_server" "agent" {
  count        = var.agent_count
  name         = "quizacrowd-agent-${count.index + 1}"
  server_type  = var.server_type
  image        = "ubuntu-24.04"
  location     = var.location
  ssh_keys     = [hcloud_ssh_key.admin.id]
  firewall_ids = [hcloud_firewall.nodes.id]
  labels       = merge(local.labels, { role = "agent" })

  public_net {
    ipv4_enabled = true
    ipv6_enabled = true
  }

  network {
    network_id = hcloud_network.cluster.id
    ip         = local.agent_ips[count.index]
  }

  user_data = templatefile("${path.module}/cloud-init-agent.yaml.tftpl", {
    k3s_version = var.k3s_version
    k3s_token   = random_password.k3s_token.result
    k3s_server  = local.k3s_server
    node_ip     = local.agent_ips[count.index]
  })

  depends_on = [hcloud_network_subnet.nodes, hcloud_server.server]

  lifecycle {
    ignore_changes = [user_data, ssh_keys]
  }
}
