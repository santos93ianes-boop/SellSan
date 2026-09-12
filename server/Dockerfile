FROM node:22-alpine
WORKDIR /app
COPY package*.json ./
RUN npm install --omit=dev
COPY . .
RUN mkdir -p /app/data
ENV PORT=8080 DATA_DIR=/app/data
EXPOSE 8080
CMD ["npm","start"]
