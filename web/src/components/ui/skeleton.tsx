import { cn } from "cn"

/**
 * A grey placeholder bar. `motion-safe:` means the gentle pulse only runs
 * for people who have not asked for reduced motion; everyone else gets a
 * still bar. There is no shimmer.
 */
function Skeleton({ className, ...props }: React.ComponentProps<"div">) {
  return (
    <div
      data-slot="skeleton"
      aria-hidden="true"
      className={cn("rounded-md bg-app-border motion-safe:animate-pulse", className)}
      {...props}
    />
  )
}

export { Skeleton }
