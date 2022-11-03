import { screen } from "@testing-library/react";

import { orgServer } from "../__mocks__/OrganizationMockServer";
import {
    makeOktaHook,
    renderWithFullAppContext,
} from "../utils/CustomRenderUtils";
import { mockSessionContext } from "../contexts/__mocks__/SessionContext";
import { MemberType } from "../hooks/UseOktaMemberships";

import SenderModeBanner from "./SenderModeBanner";

describe("SenderModeBanner", () => {
    beforeAll(() => {
        orgServer.listen();
    });
    afterEach(() => orgServer.resetHandlers());
    afterAll(() => orgServer.close());

    test("renders when sender is testing", async () => {
        mockSessionContext.mockReturnValue({
            oktaToken: {
                accessToken: "TOKEN",
            },
            activeMembership: {
                memberType: MemberType.SENDER,
                parsedName: "testOrg",
                service: "testSender",
            },
            dispatch: () => {},
            initialized: true,
        });
        renderWithFullAppContext(
            <SenderModeBanner />,
            makeOktaHook({
                authState: {
                    isAuthenticated: true,
                },
            })
        );
        const text = await screen.findByText("Learn more about onboarding.");
        expect(text).toBeInTheDocument();
    });
});
