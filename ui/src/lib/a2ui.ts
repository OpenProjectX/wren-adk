import type { A2uiMessage } from "@a2ui/web_core/v0_9";
import { basicCatalog } from "@a2ui/react/v0_9";

export const starterSurfaceMessages: A2uiMessage[] = [
  {
    version: "v0.9",
    createSurface: {
      surfaceId: "starter",
      catalogId: basicCatalog.id,
      theme: { primaryColor: "#2457d6" },
    },
  },
  {
    version: "v0.9",
    updateComponents: {
      surfaceId: "starter",
      components: [
        {
          id: "root",
          component: "Card",
          child: "starter-content",
        },
        {
          id: "starter-content",
          component: "Column",
          children: ["starter-kicker", "starter-title", "starter-copy", "starter-actions"],
        },
        {
          id: "starter-kicker",
          component: "Text",
          text: "SEMANTIC ANALYTICS",
          variant: "caption",
        },
        {
          id: "starter-title",
          component: "Text",
          text: "What would you like to understand?",
          variant: "h2",
        },
        {
          id: "starter-copy",
          component: "Text",
          text: "Choose a starting point or ask a question below. Wren keeps every query inside your governed model.",
          variant: "body",
        },
        {
          id: "starter-actions",
          component: "Row",
          children: ["models-button", "orders-button", "revenue-button"],
          align: "center",
        },
        {
          id: "models-button",
          component: "Button",
          child: "models-label",
          variant: "primary",
          action: {
            event: {
              name: "submit_prompt",
              context: { prompt: "Show me the available data models and briefly describe each one." },
            },
          },
        },
        { id: "models-label", component: "Text", text: "Explore models" },
        {
          id: "orders-button",
          component: "Button",
          child: "orders-label",
          action: {
            event: {
              name: "submit_prompt",
              context: { prompt: "Break down orders by status and highlight the largest segment." },
            },
          },
        },
        { id: "orders-label", component: "Text", text: "Orders by status" },
        {
          id: "revenue-button",
          component: "Button",
          child: "revenue-label",
          action: {
            event: {
              name: "submit_prompt",
              context: { prompt: "Show the monthly revenue trend and summarize the most important change." },
            },
          },
        },
        { id: "revenue-label", component: "Text", text: "Revenue trend" },
      ],
    },
  },
];
