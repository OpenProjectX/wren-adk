import { describe, expect, test } from "bun:test";
import { readA2uiMessages, readToolResults } from "./adk";

describe("ADK A2UI event parsing", () => {
  test("extracts messages returned by render_a2ui", () => {
    const messages = [
      {
        version: "v0.9" as const,
        createSurface: {
          surfaceId: "result",
          catalogId: "https://a2ui.org/specification/v0_9/catalogs/basic/catalog.json",
        },
      },
      {
        version: "v0.9" as const,
        updateComponents: {
          surfaceId: "result",
          components: [{ id: "root", component: "Text", text: "Ready" }],
        },
      },
    ];

    const extracted = readA2uiMessages({
      content: {
        parts: [
          {
            functionResponse: {
              name: "render_a2ui",
              response: { messages },
            },
          },
        ],
      },
    });

    expect(extracted).toEqual(messages);
  });

  test("does not treat ordinary JSON tool results as A2UI", () => {
    const extracted = readA2uiMessages({
      content: {
        parts: [{ functionResponse: { name: "run_sql", response: { rows: [{ orders: 212 }] } } }],
      },
    });

    expect(extracted).toEqual([]);
  });

  test("exposes a rejected A2UI response as a failed tool result", () => {
    const results = readToolResults({
      content: {
        parts: [
          {
            functionResponse: {
              id: "call-1",
              name: "render_a2ui",
              response: {
                error: "The A2UI surface was rejected: updateComponents.surfaceId is missing.",
              },
            },
          },
        ],
      },
    });

    expect(results).toEqual([
      {
        id: "call-1",
        name: "render_a2ui",
        error: "The A2UI surface was rejected: updateComponents.surfaceId is missing.",
      },
    ]);
  });
});
