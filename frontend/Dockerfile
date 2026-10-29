# syntax=docker/dockerfile:1
FROM node:24.22.1-alpine AS build
WORKDIR /app

# Optional TLS-inspection CA for dependency downloads (empty by default; never in the runtime image).
RUN --mount=type=secret,id=extra_ca,required=false \
    if [ -s /run/secrets/extra_ca ]; then export NODE_EXTRA_CA_CERTS=/run/secrets/extra_ca; fi; \
    npm install --global pnpm@10.20.0

COPY package.json pnpm-lock.yaml ./
RUN --mount=type=secret,id=extra_ca,required=false \
    --mount=type=cache,target=/root/.local/share/pnpm/store \
    if [ -s /run/secrets/extra_ca ]; then export NODE_EXTRA_CA_CERTS=/run/secrets/extra_ca; fi; \
    pnpm install --frozen-lockfile

COPY . .
RUN pnpm build

FROM nginx:1.30.6-alpine
COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/build /usr/share/nginx/html
EXPOSE 8080
