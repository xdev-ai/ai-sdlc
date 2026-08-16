package main

import (
	"context"
	"flag"
	"github.com/hashicorp/terraform-plugin-framework/providerserver"
	"github.com/xdev-ai/terraform-provider-aisdlc/provider"
	"log"
)

func main() {
	var debug bool
	flag.BoolVar(&debug, "debug", false, "serve the provider with debugger support")
	flag.Parse()
	options := providerserver.ServeOpts{Address: "registry.terraform.io/xdev-ai/aisdlc", Debug: debug}
	if err := providerserver.Serve(context.Background(), provider.New("dev"), options); err != nil {
		log.Fatal(err)
	}
}
