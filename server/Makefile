BINARY := vaultforge-server

.PHONY: build run cross release clean

build:
	go build -o $(BINARY) .

run: build
	VF_DATA_DIR=./data ./$(BINARY)

# 交叉编译 Linux x86_64（用于云服务器部署）
cross:
	mkdir -p dist
	GOOS=linux GOARCH=amd64 go build -o dist/$(BINARY)-linux-amd64 .

# 构建发布包（amd64 + arm64 tarball + SHA256SUMS）
release:
	./scripts/release.sh

clean:
	rm -rf $(BINARY) dist