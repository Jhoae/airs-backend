FROM node:22-alpine

WORKDIR /simulator

COPY package.json package-lock.json ./
RUN npm ci --omit=dev --ignore-scripts

COPY publish-telemetry.mjs ./

CMD ["node", "publish-telemetry.mjs"]
