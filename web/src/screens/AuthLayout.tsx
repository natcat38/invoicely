import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

/**
 * The frame the three signed-out screens share: sign in, register, and the
 * forced password change.
 *
 * <p>Deliberately plain. The Design Direction puts all of this product's
 * personality in the invoice document, and none in the app chrome — a login
 * page dressed up in serif and ledger rules would spend the effect before the
 * user ever sees a document.
 */
export function AuthLayout({
  title,
  subtitle,
  children,
  footer,
}: {
  title: string;
  subtitle?: string;
  children: React.ReactNode;
  footer?: React.ReactNode;
}) {
  return (
    <div className="grid min-h-dvh place-items-center bg-app-bg p-4">
      <div className="w-full max-w-sm space-y-6">
        <div className="space-y-1 text-center">
          {/* The one place the serif appears outside a document: the product's
              own name is a wordmark, not app chrome. */}
          <p className="font-serif text-2xl text-app-text">Invoicely</p>
        </div>

        <Card>
          <CardHeader>
            <CardTitle>{title}</CardTitle>
            {subtitle ? <CardDescription>{subtitle}</CardDescription> : null}
          </CardHeader>
          <CardContent>{children}</CardContent>
        </Card>

        {footer ? <div className="text-center">{footer}</div> : null}
      </div>
    </div>
  );
}
