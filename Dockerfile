FROM node:22-bookworm-slim AS server-deps
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends python3 make g++ \
    && rm -rf /var/lib/apt/lists/*
COPY server/package*.json ./
RUN npm ci --omit=dev

FROM node:22-bookworm-slim AS api
ENV NODE_ENV=production
WORKDIR /app
COPY --from=server-deps /app/node_modules ./node_modules
COPY server/package*.json ./
COPY server/src ./src
COPY server/scripts ./scripts
USER node
CMD ["node", "src/index.js"]

FROM node:22-bookworm-slim AS client-build
WORKDIR /app
COPY client/package*.json ./
RUN npm ci
COPY client/ ./
RUN npm run build

FROM nginx:stable-alpine AS web
COPY deploy/nginx-container.conf /etc/nginx/nginx.conf
COPY --from=client-build /app/dist /usr/share/nginx/html
USER nginx
ENTRYPOINT ["nginx", "-g", "daemon off;"]
