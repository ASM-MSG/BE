#!/bin/bash
set -eux
apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y docker.io docker-compose-v2 amazon-ecr-credential-helper jq postgresql-client redis-tools
usermod -aG docker ubuntu
mkdir -p /home/ubuntu/.docker && echo '{"credsStore":"ecr-login"}' > /home/ubuntu/.docker/config.json && chown -R ubuntu:ubuntu /home/ubuntu/.docker
fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile && echo '/swapfile none swap sw 0 0' >> /etc/fstab
touch /home/ubuntu/READY
