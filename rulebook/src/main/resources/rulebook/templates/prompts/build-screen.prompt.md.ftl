---
description: Build an Angular component and REST endpoint from a BMS map and the program behind it
mode: agent
---

Build the screen for BMS map ${r"${input:map}"}.

1. Call `screen_flow` for its fields, owning programs, validations and transitions.
2. Make one component in the style of the existing ones. Each named DFHMDF is a form control with its length as maxlength.
3. Call `business_rules` on the owning programs. Validations belong in the service, and may be repeated in the form for usability.
4. The COMMAREA becomes the request and response DTOs.
