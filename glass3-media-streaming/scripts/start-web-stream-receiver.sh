#!/usr/bin/env sh
set -eu

PROJECT_ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
RECEIVER_DIR="$PROJECT_ROOT/web-stream-receiver"

if [ ! -d "$RECEIVER_DIR/node_modules" ]; then
  echo "首次运行，正在安装 PC 接收端依赖..."
  (cd "$RECEIVER_DIR" && npm ci)
fi

LAN_IP=""
RECEIVER_PORT=${PORT:-8080}
if command -v ipconfig >/dev/null 2>&1; then
  LAN_IP=$(ipconfig getifaddr en0 2>/dev/null || true)
  if [ -z "$LAN_IP" ]; then
    LAN_IP=$(ipconfig getifaddr en1 2>/dev/null || true)
  fi
elif command -v hostname >/dev/null 2>&1; then
  LAN_IP=$(hostname -I 2>/dev/null | awk '{print $1}' || true)
fi

if [ -n "$LAN_IP" ]; then
  echo "Glass3 浏览器接收页面：http://$LAN_IP:$RECEIVER_PORT/"
  echo "眼镜端填写的信令地址：ws://$LAN_IP:$RECEIVER_PORT/ws"
else
  echo "Glass3 浏览器接收页面：http://PC局域网IP:$RECEIVER_PORT/"
  echo "眼镜端填写的信令地址：ws://PC局域网IP:$RECEIVER_PORT/ws"
fi

cd "$RECEIVER_DIR"
exec npm start
