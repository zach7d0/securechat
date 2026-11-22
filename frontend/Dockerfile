# syntax=docker/dockerfile:1
FROM node:22.17-alpine AS build
WORKDIR /src
COPY package.json package-lock.json ./
# patches/ are applied by the postinstall hook (patch-package), e.g. the ts-mls 1.6.4 tree-validation fix
COPY patches ./patches
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.27-alpine
# A template: at start-up the image's envsubst step writes conf.d/default.conf, filling in SECURECHAT_* only.
COPY nginx.conf /etc/nginx/templates/default.conf.template
ENV NGINX_ENVSUBST_FILTER=^SECURECHAT_ \
    SECURECHAT_ONBOARDING=true
COPY --from=build /src/dist /usr/share/nginx/html
EXPOSE 8080
HEALTHCHECK --interval=10s --timeout=3s --retries=5 CMD wget -qO- http://127.0.0.1:8080/healthz || exit 1
