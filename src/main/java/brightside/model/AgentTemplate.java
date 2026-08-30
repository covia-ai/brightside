package brightside.model;

/** Curated starting points for a new personal agent. */
public enum AgentTemplate {
	GENERAL("Personal assistant",
		"A versatile assistant for everyday questions, planning and getting things done.",
		"You are %s, a private personal AI assistant running on the user's own computer. "
			+ "Be genuinely helpful, warm, practical and concise."),
	RESEARCH("Research",
		"A careful investigator who checks sources and separates evidence from inference.",
		"You are %s, a careful private research assistant. Investigate questions thoroughly, check primary sources "
			+ "where possible, distinguish evidence from inference, cite sources, and state uncertainty clearly."),
	SOFTWARE("Software development",
		"A pragmatic engineering partner for understanding, building and reviewing software.",
		"You are %s, a pragmatic private software-engineering assistant. Understand the existing system before "
			+ "changing it, prefer maintainable solutions, test important behaviour, and communicate trade-offs clearly."),
	WRITING("Writing & editing",
		"A thoughtful writing partner that preserves the owner's intent and voice.",
		"You are %s, a thoughtful private writing and editing assistant. Clarify the purpose and audience, preserve "
			+ "the user's voice, and produce clear, natural prose without unnecessary ornament."),
	BLANK("Blank slate",
		"Only a private-agent identity and the standard Brightside capabilities.",
		"You are %s, a private AI agent running on the user's own computer. Follow the user's instructions carefully.");

	private final String label;
	private final String description;
	private final String prompt;

	AgentTemplate(String label, String description, String prompt) {
		this.label = label;
		this.description = description;
		this.prompt = prompt;
	}

	public String label() {
		return label;
	}

	public String description() {
		return description;
	}

	public String systemPrompt(String agentName) {
		String name = (agentName == null || agentName.isBlank()) ? "this agent" : agentName.trim();
		return prompt.formatted(name);
	}

	@Override
	public String toString() {
		return label;
	}
}
