# TCP pass-through to Traefik's NodePorts on every node, over the private network.
resource "hcloud_load_balancer" "web" {
  name               = "quizacrowd"
  load_balancer_type = "lb11"
  location           = var.location
  labels             = local.labels
}

resource "hcloud_load_balancer_network" "web" {
  load_balancer_id = hcloud_load_balancer.web.id
  network_id       = hcloud_network.cluster.id
  ip               = "10.0.1.100"
  depends_on       = [hcloud_network_subnet.nodes]
}

resource "hcloud_load_balancer_target" "nodes" {
  type             = "label_selector"
  load_balancer_id = hcloud_load_balancer.web.id
  label_selector   = "project=quizacrowd"
  use_private_ip   = true
  depends_on       = [hcloud_load_balancer_network.web]
}

resource "hcloud_load_balancer_service" "http" {
  load_balancer_id = hcloud_load_balancer.web.id
  protocol         = "tcp"
  listen_port      = 80
  destination_port = 30080

  health_check {
    protocol = "tcp"
    port     = 30080
    interval = 10
    timeout  = 5
    retries  = 3
  }
}

resource "hcloud_load_balancer_service" "https" {
  load_balancer_id = hcloud_load_balancer.web.id
  protocol         = "tcp"
  listen_port      = 443
  destination_port = 30443

  health_check {
    protocol = "tcp"
    port     = 30443
    interval = 10
    timeout  = 5
    retries  = 3
  }
}
