package io.github.mrsapien.revass;

import org.springframework.boot.SpringApplication;

public class TestRevenueAssuranceApplication {

	public static void main(String[] args) {
		SpringApplication.from(RevenueAssuranceApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
